package com.xjjk.agent.chat.service.stream;

import com.xjjk.agent.chat.domain.MessageStatus;
import com.xjjk.agent.chat.domain.memory.ChatContextSelection;
import com.xjjk.agent.chat.service.memory.ChatContextPreparationService;
import com.xjjk.agent.chat.service.model.AiChatService;
import com.xjjk.agent.chat.service.turn.ChatTurnPreparationService;
import com.xjjk.agent.chat.stream.ChatSseSession;
import com.xjjk.agent.chat.stream.ChatStreamControl;
import com.xjjk.agent.chat.stream.ChatStreamError;
import com.xjjk.agent.common.exception.BusinessException;
import com.xjjk.agent.identity.domain.AgentIdentity;
import com.xjjk.agent.product.tool.ProductToolRequestContext;
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

    public void run(
            String conversationId,
            String message,
            AgentIdentity identity,
            ChatStreamControl control,
            ChatSseSession session,
            String fallbackRequestId
    ) {
        // 每次请求独享一个执行上下文；Spring 单例服务中不保存正文、状态等可变数据。
        ChatTurnExecution execution = new ChatTurnExecution(fallbackRequestId);
        try {
            execute(conversationId, message, identity, control, session, execution);
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
            String conversationId,
            String message,
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
        execution.prepared(preparationService.prepare(conversationId, identity, message));
        if (control.isStopRequested()) {
            return;
        }

        // 第二步：先把正式 conversationId/requestId 告知前端，后续事件都可据此关联本轮请求。
        session.session(execution.turn.conversationId(), execution.requestId);

        // 第三步：基于 MySQL 稳定游标读取短期记忆，执行 Token 预算和上下文裁剪。
        // 该阶段可能命中 Redis，也可能 fail-open 回源 MySQL，但不会绕过会话归属校验。
        ChatContextSelection selection = contextService.prepare(execution.turn, message, control);
        if (control.isStopRequested()) {
            return;
        }

        // 第四步：上下文准备完成后才声明正在生成，随后逐片消费并发送模型流。
        session.generating();

        // 进入模型调用前预置失败结果；只有模型流正常结束后才会计算最终成功状态。
        execution.error = ChatStreamError.forStatus(MessageStatus.FAILED);
        consumeModel(message, selection, identity, control, session, execution);

        if (!control.isStopRequested()) {
            // 第五步：根据正文和 finishReason 判断 SUCCESS、OUTPUT_LIMIT、
            // EMPTY_RESPONSE 或 INCOMPLETE，真正落库与 SSE 终态由 finalizer 处理。
            execution.status = completedStatus(execution);
            execution.error = ChatStreamError.forStatus(execution.status);
        }
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
        ProductToolRequestContext toolContext = new ProductToolRequestContext(
                execution.requestId,
                identity,
                result -> {
                    try {
                        // 商品图片和分页等完整结构只走 SSE result，不进入模型上下文。
                        session.result("product-list", result);
                    } catch (IOException exception) {
                        throw new UncheckedIOException(exception);
                    }
                });
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
