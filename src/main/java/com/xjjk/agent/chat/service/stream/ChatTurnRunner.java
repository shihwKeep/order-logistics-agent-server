package com.xjjk.agent.chat.service.stream;

import com.xjjk.agent.chat.action.ChatActionDispatcher;
import com.xjjk.agent.chat.action.ChatActionType;
import com.xjjk.agent.chat.api.dto.ChatStreamRequest;
import com.xjjk.agent.chat.domain.MessageStatus;
import com.xjjk.agent.chat.domain.memory.ChatContextSelection;
import com.xjjk.agent.chat.service.memory.ChatContextPreparationService;
import com.xjjk.agent.chat.service.model.AiChatService;
import com.xjjk.agent.chat.result.ChatToolResultRecorder;
import com.xjjk.agent.chat.result.PendingMessageResult;
import com.xjjk.agent.chat.service.turn.ChatTurnPreparationService;
import com.xjjk.agent.chat.stream.ChatSseSession;
import com.xjjk.agent.chat.stream.ChatStreamControl;
import com.xjjk.agent.chat.stream.ChatStreamError;
import com.xjjk.agent.common.exception.BusinessException;
import com.xjjk.agent.identity.domain.AgentIdentity;
import com.xjjk.agent.tool.AgentToolRequestContext;
import com.xjjk.agent.tool.ToolCallGuard;
import com.xjjk.agent.tool.ToolUiResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.CancellationException;

/**
 * 单轮聊天执行器：准备业务记录、准备上下文、消费模型流。
 * 不开启覆盖整轮对话的事务；所有退出路径统一进入收尾器。
 * 单例中只保存依赖，可变状态由每次调用独立创建。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatTurnRunner {

    private final ChatTurnPreparationService preparationService;
    private final ChatContextPreparationService contextService;
    private final AiChatService aiChatService;
    private final ChatTurnFinalizer finalizer;
    private final ChatToolResultRecorder resultRecorder;
    private final ChatActionDispatcher actionDispatcher;

    public void run(
            ChatStreamRequest request,
            AgentIdentity identity,
            ChatStreamControl control,
            ChatSseSession session,
            String fallbackRequestId
    ) {
        // 每次请求独享一个执行上下文；Spring 单例服务中不保存正文、状态等可变数据。
        ChatTurnExecution execution = new ChatTurnExecution(fallbackRequestId);
        try {
            execute(request, identity, control, session, execution);
        } catch (BusinessException exception) {
            execution.status = MessageStatus.FAILED;
            execution.error = new ChatStreamError(
                    exception.errorCode().code(), exception.errorCode().message());
        } catch (IOException exception) {
            execution.status = MessageStatus.OUTPUT_ERROR;
            execution.error = ChatStreamError.forStatus(execution.status);
            execution.outputBroken = true;
        } catch (CancellationException exception) {
            // 正常停止由收尾器读取首个停止原因；无停止信号则按执行失败处理。
            if (!control.isStopRequested()) {
                failed(execution, exception);
            }
        } catch (RuntimeException exception) {
            if (hasCause(exception, UncheckedIOException.class)) {
                execution.status = MessageStatus.OUTPUT_ERROR;
                execution.error = ChatStreamError.forStatus(execution.status);
                execution.outputBroken = true;
            } else {
                failed(execution, exception);
            }
        } finally {
            // 无论正常完成、模型失败、SSE 断开还是任务取消，都只能从这里进入统一收尾。
            // 这样可以避免某个异常分支遗漏会话占用释放或消息状态落库。
            finalizer.finish(execution, control, session);
        }
    }

    private void execute(
            ChatStreamRequest request,
            AgentIdentity identity,
            ChatStreamControl control,
            ChatSseSession session,
            ChatTurnExecution execution
    ) throws IOException {
        if (control.isStopRequested()) {
            return;
        }

        // 第一步：通过短事务创建本轮 USER/ASSISTANT 消息并占用会话。
        // 仅在任务真正获得线程执行时才创建记录；prepare 返回时开始事务已经提交。
        execution.prepared(preparationService.prepare(
                request.conversationId(), identity, request.message()));
        if (control.isStopRequested()) {
            return;
        }

        // 第二步：先把正式 conversationId/requestId 告知前端，后续事件都可据此关联本轮请求。
        session.session(execution.turn.conversationId(), execution.requestId);

        if (request.action() != null) {
            executeAction(request, identity, session, execution);
            return;
        }

        // 第三步：基于 MySQL 稳定游标读取短期记忆，执行 Token 预算和上下文裁剪。
        // 该阶段可能命中 Redis，也可能 fail-open 回源 MySQL，但不会绕过会话归属校验。
        ChatContextSelection selection = contextService.prepare(
                execution.turn, request.message(), control);
        if (control.isStopRequested()) {
            return;
        }

        // 第四步：上下文准备完成后才声明正在生成，随后逐片消费并发送模型流。
        session.generating();

        // 进入模型调用前预置失败结果；只有模型流正常结束后才会计算最终成功状态。
        execution.error = ChatStreamError.forStatus(MessageStatus.FAILED);
        consumeModel(request.message(), selection, identity, control, session, execution);

        if (!control.isStopRequested()) {
            // 第五步：根据正文和 finishReason 判断 SUCCESS、OUTPUT_LIMIT、
            // EMPTY_RESPONSE 或 INCOMPLETE，真正落库与 SSE 终态由 finalizer 处理。
            execution.status = completedStatus(execution);
            execution.error = ChatStreamError.forStatus(execution.status);
        }
    }

    private void executeAction(
            ChatStreamRequest request,
            AgentIdentity identity,
            ChatSseSession session,
            ChatTurnExecution execution) throws IOException {
        /*
         * 卡片动作是后端白名单命令：不加载对话上下文，也不让模型判断调用哪个工具。
         * 但它仍使用开始事务产生的可信身份和 requestId，并由同一个 finalizer
         * 原子保存用户消息、固定助手正文、结构化结果和稳定历史游标。
         */
        // 动作状态由后端白名单类型决定，不能由前端提交任意状态文案。
        switch (ChatActionType.parse(request.action().type())) {
            case QUERY_ORDER_LOGISTICS -> session.queryingLogistics();
            case QUERY_CUSTOMER_ORDERS -> session.queryingCustomerOrders();
        }
        ChatActionDispatcher.DispatchResult dispatched = actionDispatcher.dispatch(
                request.action(), identity, execution.requestId);
        // 客户卡片可能已经过期；无法解析时仍保存固定回答，但不会伪造空订单结果。
        if (dispatched.uiResult() != null) {
            publishToolResult(dispatched.uiResult(), session, execution);
        }

        execution.content.append(dispatched.assistantText());
        session.delta(dispatched.assistantText());
        execution.metrics.markFirstDeltaSent();
        execution.finishReason = "ACTION_COMPLETED";
        execution.status = MessageStatus.SUCCESS;
        execution.error = null;
    }

    private void consumeModel(
            String message,
            ChatContextSelection selection,
            AgentIdentity identity,
            ChatStreamControl control,
            ChatSseSession session,
            ChatTurnExecution execution
    ) throws IOException {
        // try-with-resources 保证正常结束、异常和取消时都关闭上游模型流，释放 HTTP 连接。
        // Guard 是严格的“单轮状态”：本轮同参调用共享结果，最多允许三个不同调用键。
        // 必须在这里随请求创建，不能注入为单例，否则不同用户/轮次会串结果和额度。
        AgentToolRequestContext toolContext = new AgentToolRequestContext(
                execution.requestId,
                identity,
                result -> publishToolResultUnchecked(result, session, execution),
                new ToolCallGuard(3));
        try (var responses = aiChatService
                .stream(message, selection, toolContext)
                .toStream(1)) {
            var iterator = responses.iterator();
            while (!control.isStopRequested() && iterator.hasNext()) {
                ChatResponse response = iterator.next();
                if (control.isStopRequested()) {
                    break;
                }
                acceptResponse(response, session, execution);
            }
        }
    }

    private void publishToolResultUnchecked(
            ToolUiResult result,
            ChatSseSession session,
            ChatTurnExecution execution) {
        try {
            publishToolResult(result, session, execution);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private void publishToolResult(
            ToolUiResult result,
            ChatSseSession session,
            ChatTurnExecution execution) throws IOException {
        synchronized (execution) {
            /*
             * 固定顺序不能调整：先完成字段、JSON 和 UTF-8 字节上限校验，
             * 再登记到本轮待落库集合，最后才向前端发送 SSE result。
             */
            PendingMessageResult pending = resultRecorder.prepare(
                    result, execution.nextResultSequence());
            execution.addResult(pending);
            session.result(result);
        }
    }

    private void acceptResponse(
            ChatResponse response,
            ChatSseSession session,
            ChatTurnExecution execution
    ) throws IOException {
        // 用量片段可能不带正文，必须先收集用量。
        execution.metrics.accept(response);
        var generation = response.getResult();
        if (generation == null) {
            return;
        }
        var metadata = generation.getMetadata();
        if (metadata != null && StringUtils.hasText(metadata.getFinishReason())) {
            execution.finishReason = metadata.getFinishReason();
        }
        var output = generation.getOutput();
        if (output == null) {
            return;
        }
        String text = output.getText();
        if (text != null && !text.isEmpty()) {
            // 发送失败仍保留模型已经返回的部分正文，交给收尾事务处理。
            execution.content.append(text);
            session.delta(text);
            execution.metrics.markFirstDeltaSent();
        }
    }

    private MessageStatus completedStatus(ChatTurnExecution execution) {
        if (!StringUtils.hasText(execution.content.toString())) {
            return MessageStatus.EMPTY_RESPONSE;
        }
        if ("length".equalsIgnoreCase(execution.finishReason)) {
            return MessageStatus.OUTPUT_LIMIT;
        }
        return "stop".equalsIgnoreCase(execution.finishReason)
                ? MessageStatus.SUCCESS : MessageStatus.INCOMPLETE;
    }

    private void failed(ChatTurnExecution execution, RuntimeException exception) {
        execution.status = MessageStatus.FAILED;
        execution.error = execution.turn == null
                ? ChatStreamError.preparationFailed()
                : ChatStreamError.forStatus(execution.status);
        // 不输出正文、凭据和供应商原始异常消息。
        log.warn("Chat execution failed: requestId={}, exceptionType={}",
                execution.requestId, exception.getClass().getSimpleName());
    }

    private boolean hasCause(Throwable exception, Class<? extends Throwable> causeType) {
        Throwable current = exception;
        while (current != null) {
            if (causeType.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
