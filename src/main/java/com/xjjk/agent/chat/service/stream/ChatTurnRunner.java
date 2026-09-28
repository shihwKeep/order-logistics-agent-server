package com.xjjk.agent.chat.service.stream;

import com.xjjk.agent.chat.action.ChatActionDispatcher;
import com.xjjk.agent.chat.action.ChatActionType;
import com.xjjk.agent.chat.api.dto.ChatStreamRequest;
import com.xjjk.agent.chat.config.ChatStreamProperties;
import com.xjjk.agent.chat.domain.MessageStatus;
import com.xjjk.agent.chat.domain.memory.ChatContextSelection;
import com.xjjk.agent.chat.observation.AgentTurnTelemetry;
import com.xjjk.agent.chat.service.memory.ChatContextPreparationService;
import com.xjjk.agent.chat.service.model.AiChatService;
import com.xjjk.agent.chat.result.ChatToolResultRecorder;
import com.xjjk.agent.chat.result.PendingMessageResult;
import com.xjjk.agent.chat.routing.BusinessQueryMode;
import com.xjjk.agent.chat.routing.BusinessQueryPlan;
import com.xjjk.agent.chat.routing.BusinessQueryPlanner;
import com.xjjk.agent.chat.service.turn.ChatTurnPreparationService;
import com.xjjk.agent.chat.stream.ChatEventPublisher;
import com.xjjk.agent.chat.stream.ChatStreamControl;
import com.xjjk.agent.chat.stream.ChatStreamError;
import com.xjjk.agent.common.exception.BusinessException;
import com.xjjk.agent.identity.domain.AgentIdentity;
import com.xjjk.agent.memory.answer.DeterministicUserMemoryAnswerResult;
import com.xjjk.agent.memory.answer.DeterministicUserMemoryAnswerService;
import com.xjjk.agent.memory.domain.ExplicitMemoryCommandResult;
import com.xjjk.agent.memory.service.ExplicitMemoryCommandService;
import com.xjjk.agent.tool.AgentToolRequestContext;
import com.xjjk.agent.tool.ToolCallGuard;
import com.xjjk.agent.tool.ToolUiResult;
import com.xjjk.agent.tool.observation.ToolCallMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;

/**
 * 单轮聊天执行器：准备业务记录、准备上下文、消费模型流。
 * 不开启覆盖整轮对话的事务；所有退出路径统一进入收尾器。
 * 单例中只保存依赖，可变状态由每次调用独立创建。
 *
 * <p>意图按确定性从高到低依次处理：显式记忆命令、前端白名单 Action、
 * 可唯一提取参数的业务直查、本人长期记忆直答、需要新鲜结果的模型工具查询，
 * 最后才是普通 Agent 问答。前一层命中并完成后立即返回，避免重复执行下游能力。</p>
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
    private final BusinessQueryPlanner businessQueryPlanner;
    private final FreshBusinessResultGate freshBusinessResultGate;
    private final ExplicitMemoryCommandService explicitMemoryCommandService;
    private final DeterministicUserMemoryAnswerService deterministicMemoryAnswerService;
    private AgentTurnTelemetry turnTelemetry;
    private ToolCallMetrics toolCallMetrics;
    private ChatStreamProperties.ModelRetry modelRetryProperties =
            ChatStreamProperties.ModelRetry.defaults();

    /** 可选 setter 不改变既有构造签名，Spring 运行时会注入，纯单元测试可继续直接构造。 */
    @Autowired
    void setTurnTelemetry(AgentTurnTelemetry turnTelemetry) {
        this.turnTelemetry = turnTelemetry;
    }

    /** 观测组件不改变既有构造签名，便于保持现有单元测试的组装方式。 */
    @Autowired
    void setToolCallMetrics(ToolCallMetrics toolCallMetrics) {
        this.toolCallMetrics = toolCallMetrics;
    }

    /** 模型重试不改变既有构造签名，单元测试也可注入零延迟配置。 */
    @Autowired
    void setModelRetryProperties(ChatStreamProperties properties) {
        setModelRetryProperties(properties.modelRetry());
    }

    void setModelRetryProperties(ChatStreamProperties.ModelRetry properties) {
        this.modelRetryProperties = java.util.Objects.requireNonNull(
                properties, "模型流重试配置不能为空");
    }

    public void run(
            ChatStreamRequest request,
            AgentIdentity identity,
            ChatStreamControl control,
            ChatEventPublisher session,
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
        } catch (ModelStreamRetryExhaustedException exception) {
            execution.status = MessageStatus.FAILED;
            execution.error = ChatStreamError.modelStreamRetryExhausted();
            log.warn("Model stream retries exhausted: requestId={}, attempts={}, causeType={}",
                    execution.requestId,
                    exception.attempts(),
                    exception.getCause() == null
                            ? "unknown" : exception.getCause().getClass().getSimpleName());
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
            // MODEL_REQUIRED 的正文若未走完证据门禁，先替换为安全文案，禁止猜测性
            // 查询结果通过异常、截断或取消分支写入历史消息。
            if (execution.buffersModelOutput() && !execution.isBufferedOutputResolved()) {
                observeStage("result.gate",
                        () -> freshBusinessResultGate.sanitizeForPersistence(execution));
            } else {
                freshBusinessResultGate.sanitizeForPersistence(execution);
            }
            finalizer.finish(execution, control, session);
        }
    }

    private void execute(
            ChatStreamRequest request,
            AgentIdentity identity,
            ChatStreamControl control,
            ChatEventPublisher session,
            ChatTurnExecution execution
    ) throws IOException {
        if (control.isStopRequested()) {
            return;
        }

        // 第一步：通过短事务创建本轮 USER/ASSISTANT 消息并占用会话。
        // 仅在任务真正获得线程执行时才创建记录；prepare 返回时开始事务已经提交。
        if (StringUtils.hasText(request.clientRequestId())) {
            execution.prepared(observeStage("turn.prepare", () ->
                    preparationService.prepare(
                            request.conversationId(), identity, request.message(),
                            request.clientRequestId())));
        } else {
            // 仅保留给当前单元测试和旧内部调用；HTTP Bean Validation 不允许为空。
            execution.prepared(observeStage("turn.prepare", () ->
                    preparationService.prepare(
                            request.conversationId(), identity, request.message())));
        }
        if (control.isStopRequested()) {
            return;
        }

        // 第二步：先把正式 conversationId/requestId 告知前端，后续事件都可据此关联本轮请求。
        session.session(execution.turn.conversationId(), execution.requestId);

        /*
         * 意图一：显式长期记忆命令。
         * 仅普通文本请求参与判断；前端已经携带 Action 时不能把卡片动作误判成记忆命令。
         * 内部先走确定性快速解析，无法覆盖时再使用语义模型；handled=true 表示该意图
         * 已经给出保存成功、拒绝或澄清回答，本轮不再进入业务查询和普通模型。
         */
        if (request.action() == null) {
            ExplicitMemoryCommandResult memory = explicitMemoryCommandService.handle(
                    execution.turn, request.message());
            if (memory.handled()) {
                execution.intent("EXPLICIT_MEMORY");
                observeStage("intent.route", () -> { });
                // 记忆模块返回的是服务端确定性正文，直接通过 delta 输出，不再让聊天模型改写。
                session.generating();
                execution.content.append(memory.assistantText());
                session.delta(memory.assistantText());
                execution.metrics.markFirstDeltaSent();
                execution.finishReason = memory.saved()
                        ? "MEMORY_SAVED" : "MEMORY_REJECTED";
                execution.status = MessageStatus.SUCCESS;
                execution.error = null;
                return;
            }
        }

        /*
         * 意图二：前端卡片白名单 Action。
         * 用户点击“查看物流/客户订单/售后详情”等卡片按钮时，动作类型和公开业务编号
         * 已经明确，无需模型再次识别；直接交给 ChatActionDispatcher 校验白名单、
         * 认证身份、组织能力开关和参数后调用下游服务。执行完成即结束本轮路由。
         */
        if (request.action() != null) {
            execution.intent("ACTION");
            observeStage("intent.route", () -> { });
            executeAction(request, identity, session, execution);
            return;
        }

        /*
         * 意图三：当前文本的业务查询计划。
         * 只根据“当前用户消息”制定实时业务查询计划，历史消息不能决定
         * 本轮已经查过业务系统。完整且无歧义的编号查询直接进入后端白名单动作，
         * 每次发送都会重新调用下游服务，因此不会复用上一轮的结果或模型话术。
         *
         * Planner 只产生三种执行模式：
         * 1. DIRECT：业务域和完整编号都可唯一确定，直接执行固定 Action；
         * 2. MODEL_REQUIRED：确认必须取得本轮新鲜工具/知识库结果，但参数或步骤需模型判断；
         * 3. GENERAL：未强制要求实时业务结果，继续检查本人记忆问题或进入普通问答。
         */
        BusinessQueryPlan queryPlan = businessQueryPlanner.plan(request.message());
        // 将计划保存到本轮执行上下文，后续工具结果类型校验和正文缓冲门禁使用同一份计划。
        execution.queryPlan(queryPlan);

        /*
         * 意图三-A：确定性业务直查。
         * 例如当前消息同时包含明确物流查询意图和唯一完整订单号，可直接构造
         * QUERY_ORDER_LOGISTICS Action，减少一次模型决策并防止模型改写业务编号。
         */
        if (queryPlan.mode() == BusinessQueryMode.DIRECT) {
            execution.intent("DIRECT_BUSINESS");
            observeStage("intent.route", () -> { });
            executeAction(
                    new ChatStreamRequest(
                            request.conversationId(),
                            request.message(),
                            queryPlan.directAction(),
                            request.clientRequestId()),
                    identity,
                    session,
                    execution);
            return;
        }

        /*
         * 意图四：询问本人长期记忆中的明确属性。
         * 仅 GENERAL 模式尝试，避免把“我的订单多大了”等实时业务问题误判成年龄问题。
         * 分类器只接管能够唯一识别的年龄、称呼、语言、职业、技术栈等封闭问题，
         * 命中后直接查询 MySQL 权威记忆并生成固定回答，不再让模型猜测用户画像。
         */
        if (queryPlan.mode() == BusinessQueryMode.GENERAL) {
            DeterministicUserMemoryAnswerResult memoryAnswer =
                    deterministicMemoryAnswerService.answer(
                            identity, request.message(), execution.requestId);
            if (control.isStopRequested()) {
                return;
            }
            if (memoryAnswer.handled()) {
                execution.intent("MEMORY_RECALL");
                observeStage("intent.route", () -> { });
                // 直答正文已经过事实终审和固定模板渲染，直接输出并结束意图责任链。
                session.generating();
                execution.content.append(memoryAnswer.assistantText());
                session.delta(memoryAnswer.assistantText());
                execution.metrics.markFirstDeltaSent();
                execution.finishReason = "MEMORY_RECALLED";
                execution.status = MessageStatus.SUCCESS;
                execution.error = null;
                return;
            }
        }

        /*
         * 意图五：进入模型处理。
         * 到达这里的请求只有两类：
         * 1. MODEL_REQUIRED：实时业务或企业知识意图已经确认，模型负责选择工具/补全步骤，
         *    但必须产生 queryPlan 允许的本轮新鲜 result，回答正文才允许发送；
         * 2. GENERAL：普通问答或未被保守规则唯一识别的复杂表达，模型可在本轮已注册的
         *    权限工具中自行判断是否调用，也可以直接生成通用回答。
         */

        // 第四步：进入模型前统一准备短期、摘要、长期记忆及系统提示词，并执行 Token 裁剪。
        // 短期快照可能命中 Redis，也可能 fail-open 回源 MySQL，但不会绕过会话归属校验。
        execution.intent(queryPlan.mode() == BusinessQueryMode.MODEL_REQUIRED
                ? (queryPlan.acceptedResultKinds().contains("knowledge-citations")
                        ? "KNOWLEDGE" : "MODEL_REQUIRED")
                : "GENERAL");
        observeStage("intent.route", () -> { });

        ChatContextSelection selection = observeStage("context.load", () ->
                contextService.prepare(execution.turn, request.message(), control));
        if (control.isStopRequested()) {
            return;
        }

        // 第五步：上下文准备完成后才声明正在生成，随后逐片消费模型流。
        // GENERAL 正文可正常流式发送；MODEL_REQUIRED 必须先暂存正文和工具结果，
        // 只有新鲜结果门禁确认结果类型正确后才能统一发送，防止模型伪造“已查询成功”。
        session.generating();

        // 进入模型调用前预置失败结果；只有模型流正常结束后才会计算最终成功状态。
        execution.error = ChatStreamError.forStatus(MessageStatus.FAILED);
        observeIoStage("model.stream", () -> consumeModel(
                request.message(), selection, identity, control, session, execution));

        if (!control.isStopRequested()) {
            // 第六步：根据正文和 finishReason 判断 SUCCESS、OUTPUT_LIMIT、
            // EMPTY_RESPONSE 或 INCOMPLETE，真正落库与 SSE 终态由 finalizer 处理。
            execution.status = completedStatus(execution);
            execution.error = ChatStreamError.forStatus(execution.status);
            if (execution.status == MessageStatus.SUCCESS
                    && execution.buffersModelOutput()) {
                /*
                 * 模型实时查询只有在本轮确实产生了允许类型的结构化结果后，
                 * 才统一发布卡片与回答。缺失结果时会丢弃模型的“已查询”话术，
                 * 改为固定安全提示，避免历史上下文诱导出假成功。
                 */
                observeIoStage("result.gate",
                        () -> freshBusinessResultGate.flush(execution, session));
            }
        }
    }

    private void executeAction(
            ChatStreamRequest request,
            AgentIdentity identity,
            ChatEventPublisher session,
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
            case QUERY_AFTER_SALE_DETAIL -> session.queryingAfterSaleDetail();
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
            ChatEventPublisher session,
            ChatTurnExecution execution
    ) throws IOException {
        // try-with-resources 保证正常结束、异常和取消时都关闭上游模型流，释放 HTTP 连接。
        // Guard 是严格的“单轮状态”：本轮同参调用共享结果，最多允许三个不同调用键。
        // 必须在这里随请求创建，不能注入为单例，否则不同用户/轮次会串结果和额度。
        AgentToolRequestContext toolContext = new AgentToolRequestContext(
                execution.requestId,
                identity,
                result -> publishToolResultUnchecked(result, session, execution),
                new ToolCallGuard(3, toolCallMetrics));
        ModelStreamRetryPolicy retryPolicy = new ModelStreamRetryPolicy(
                modelRetryProperties.maxAttempts(),
                modelRetryProperties.initialBackoff(),
                modelRetryProperties.maxBackoff(),
                modelRetryProperties.jitterRatio());

        int attempt = 1;
        while (true) {
            // 退避等待期间可能触发整轮超时或用户取消；在重新打开上游模型流前
            // 再检查一次，保证截止时间不会被重试动作突破。
            if (control.isStopRequested()) {
                throw new CancellationException("模型流重试前请求已停止");
            }
            int contentLengthBeforeAttempt = execution.content.length();
            execution.finishReason = null;
            try {
                consumeModelAttempt(
                        message, selection, toolContext, control, session, execution);
                return;
            } catch (RuntimeException exception) {
                if (control.isStopRequested()) {
                    throw exception;
                }
                if (!retryPolicy.shouldRetry(
                        exception, attempt, execution.isModelTextSent())) {
                    if (retryPolicy.isTransientFailure(exception)
                            && !execution.isModelTextSent()) {
                        if (turnTelemetry != null) {
                            turnTelemetry.recordModelRetry("EXHAUSTED");
                        }
                        throw new ModelStreamRetryExhaustedException(attempt, exception);
                    }
                    throw exception;
                }

                // MODEL_REQUIRED 的回答尚未通过门禁，不会发送给前端；重试前只回滚
                // 当前尝试生成的正文，已经暂存的可信工具结果继续由同一 Guard 复用。
                execution.truncateContent(contentLengthBeforeAttempt);
                execution.finishReason = null;
                Duration backoff = retryPolicy.backoff(attempt);
                log.warn("Retrying model stream: requestId={}, attempt={}, backoffMs={}, "
                                + "exceptionType={}",
                        execution.requestId,
                        attempt + 1,
                        backoff.toMillis(),
                        exception.getClass().getSimpleName());
                if (turnTelemetry != null) {
                    turnTelemetry.recordModelRetry("SCHEDULED");
                }
                awaitRetry(backoff);
                attempt++;
            }
        }
    }

    private void consumeModelAttempt(
            String message,
            ChatContextSelection selection,
            AgentToolRequestContext toolContext,
            ChatStreamControl control,
            ChatEventPublisher session,
            ChatTurnExecution execution
    ) throws IOException {
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

    private void awaitRetry(Duration delay) {
        if (delay.isZero()) {
            return;
        }
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CancellationException("模型流重试等待被中断");
        }
    }

    private void publishToolResultUnchecked(
            ToolUiResult result,
            ChatEventPublisher session,
            ChatTurnExecution execution) {
        try {
            publishToolResult(result, session, execution);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private void publishToolResult(
            ToolUiResult result,
            ChatEventPublisher session,
            ChatTurnExecution execution) throws IOException {
        synchronized (execution) {
            /*
             * 固定顺序不能调整：先完成字段、JSON 和 UTF-8 字节上限校验，
             * 再登记到本轮待落库集合，最后才向前端发送 SSE result。
             */
            PendingMessageResult pending;
            try {
                pending = resultRecorder.prepare(
                        result, execution.nextResultSequence());
            } catch (RuntimeException exception) {
                recordToolResult(result == null ? null : result.kind(),
                        "PROTOCOL_REJECTED");
                throw exception;
            }
            if (execution.buffersModelOutput()) {
                // 模型业务查询先暂存，待本轮结果类型校验通过后再发布和持久化。
                execution.stageResult(pending, result);
                recordToolResult(result.kind(), "STAGED");
            } else {
                execution.addResult(pending);
                session.result(result);
                recordToolResult(result.kind(), "PUBLISHED");
            }
        }
    }

    private void recordToolResult(String kind, String outcome) {
        if (toolCallMetrics != null) {
            toolCallMetrics.result(kind, outcome);
        }
    }

    private void acceptResponse(
            ChatResponse response,
            ChatEventPublisher session,
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
            // MODEL_REQUIRED 先完整缓存正文，防止工具尚未执行时就流出“已查询”话术。
            execution.content.append(text);
            if (!execution.buffersModelOutput()) {
                // 普通问答继续保持逐片流式输出；发送失败由现有收尾逻辑处理。
                // 先标记再写出：即使本次 SSE 写出抛错，也不能重试模型并重复正文。
                execution.markModelTextSent();
                session.delta(text);
                execution.metrics.markFirstDeltaSent();
            }
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

    private <T> T observeStage(String stage, Supplier<T> action) {
        return turnTelemetry == null ? action.get() : turnTelemetry.observeStage(stage, action);
    }

    private void observeStage(String stage, Runnable action) {
        if (turnTelemetry == null) {
            action.run();
        } else {
            turnTelemetry.observeStage(stage, action);
        }
    }

    private void observeIoStage(String stage, IoRunnable action) throws IOException {
        try {
            observeStage(stage, () -> {
                try {
                    action.run();
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            });
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        }
    }

    @FunctionalInterface
    private interface IoRunnable {
        void run() throws IOException;
    }
}
