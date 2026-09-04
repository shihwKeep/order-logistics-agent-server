package com.xjjk.agent.chat.api.controller;

import com.xjjk.agent.chat.api.dto.ChatStreamEvent;
import com.xjjk.agent.chat.api.dto.ChatStreamPayloads;
import com.xjjk.agent.chat.api.dto.ChatStreamRequest;
import com.xjjk.agent.chat.domain.ChatTurnContext;
import com.xjjk.agent.chat.domain.MessageStatus;
import com.xjjk.agent.chat.observation.ChatCallMetricsCollector;
import com.xjjk.agent.chat.service.model.AiChatService;
import com.xjjk.agent.chat.service.turn.ChatTurnFinishService;
import com.xjjk.agent.chat.service.turn.ChatTurnPreparationService;
import com.xjjk.agent.chat.stream.ChatStreamControl;
import com.xjjk.agent.common.exception.BusinessException;
import com.xjjk.agent.identity.domain.AgentIdentity;
import com.xjjk.agent.identity.web.CurrentAgentIdentity;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.FutureTask;

/**
 * 聊天流式接口。
 *
 * 在工作线程中准备会话、读取模型流并执行数据库收尾。
 * 模型生成过程不处于数据库事务中。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/chat")
public class ChatStreamController {

    private final ThreadPoolTaskExecutor executor;
    private final AiChatService aiChatService;
    private final ChatTurnPreparationService preparationService;
    private final ChatTurnFinishService finishService;

    public ChatStreamController(
            @Qualifier("chatStreamExecutor")
            ThreadPoolTaskExecutor executor,
            AiChatService aiChatService,
            ChatTurnPreparationService preparationService,
            ChatTurnFinishService finishService
    ) {
        this.executor = executor;
        this.aiChatService = aiChatService;
        this.preparationService = preparationService;
        this.finishService = finishService;
    }

    /**
     * 接收流式聊天请求。
     *
     * 身份在请求线程中解析，再显式传入工作线程，
     * 不在异步线程中依赖请求 ThreadLocal。
     */
    @PostMapping(
            value = "/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public SseEmitter stream(
            @Valid @RequestBody ChatStreamRequest request,
            @CurrentAgentIdentity AgentIdentity identity,
            HttpServletResponse response
    ) throws IOException {
        response.setHeader("Cache-Control", "no-cache");

        SseEmitter emitter = new SseEmitter(30_000L);
        ChatStreamControl control = new ChatStreamControl();

        // 尚未创建数据库请求时，用于关联拒绝或准备失败的错误。
        String fallbackRequestId = UUID.randomUUID().toString();

        FutureTask<Void> task = new FutureTask<>(() -> {
            sendModelEvents(
                    emitter,
                    request,
                    identity,
                    control,
                    fallbackRequestId
            );
            return null;
        });

        control.bind(task);

        emitter.onCompletion(() ->
                control.requestStop(MessageStatus.CANCELLED));

        emitter.onError(exception ->
                control.requestStop(MessageStatus.OUTPUT_ERROR));

        emitter.onTimeout(() -> {
            control.requestStop(MessageStatus.TIMEOUT);
            emitter.complete();
        });

        try {
            executor.execute(task);
        } catch (TaskRejectedException exception) {
            control.requestStop(MessageStatus.CANCELLED);

            // 任务尚未执行，没有创建消息或占用会话。
            send(emitter, "error", 1,
                    new ChatStreamPayloads.Error(
                            "CHAT_BUSY",
                            "当前请求较多，请稍后重试",
                            fallbackRequestId
                    ));

            emitter.complete();
        }

        return emitter;
    }

    /**
     * 执行一轮聊天。
     *
     * 正文和调用指标仅由当前工作线程维护。
     * 无论成功、失败还是取消，均进入统一收尾流程。
     */
    private void sendModelEvents(
            SseEmitter emitter,
            ChatStreamRequest request,
            AgentIdentity identity,
            ChatStreamControl control,
            String fallbackRequestId
    ) {
        ChatTurnContext turn = null;
        ChatCallMetricsCollector metrics = null;

        String requestId = fallbackRequestId;
        StringBuilder content = new StringBuilder();

        long sequence = 0;
        String finishReason = null;
        MessageStatus status = MessageStatus.FAILED;

        StreamError error = new StreamError(
                "CHAT_PREPARATION_FAILED",
                "聊天准备失败，请稍后重试"
        );

        boolean outputBroken = false;

        try {
            if (control.isStopRequested()) {
                return;
            }

            // 任务实际开始执行后才准备数据库记录。
            turn = preparationService.prepare(
                    request.conversationId(),
                    identity,
                    request.message()
            );

            requestId = turn.requestId();

            metrics = new ChatCallMetricsCollector(
                    turn.requestId(),
                    turn.conversationId(),
                    turn.promptVersion()
            );

            // 准备期间也可能发生取消，此时不再调用模型。
            if (control.isStopRequested()) {
                return;
            }

            send(emitter, "session", ++sequence,
                    new ChatStreamPayloads.Session(
                            turn.conversationId(),
                            turn.requestId()
                    ));

            send(emitter, "status", ++sequence,
                    new ChatStreamPayloads.Status(
                            "GENERATING",
                            "正在生成回答"
                    ));

            error = errorFor(MessageStatus.FAILED);

            // 此时开始事务已经提交，不持有数据库行锁。
            try (var responses = aiChatService
                    .stream(request.message())
                    .toStream(1)) {

                var iterator = responses.iterator();

                while (!control.isStopRequested() && iterator.hasNext()) {
                    var modelResponse = iterator.next();

                    if (control.isStopRequested()) {
                        break;
                    }

                    // 先读取用量，兼容只有用量而没有文本的响应。
                    metrics.accept(modelResponse);

                    var generation = modelResponse.getResult();
                    if (generation == null) {
                        continue;
                    }

                    var metadata = generation.getMetadata();
                    if (metadata != null) {
                        String reason = metadata.getFinishReason();
                        if (StringUtils.hasText(reason)) {
                            finishReason = reason;
                        }
                    }

                    var output = generation.getOutput();
                    if (output == null) {
                        continue;
                    }

                    String text = output.getText();
                    if (text != null && !text.isEmpty()) {
                        // 先保留已收到的内容，发送失败时也能保存部分回答。
                        content.append(text);

                        send(emitter, "delta", ++sequence,
                                new ChatStreamPayloads.Delta(text));

                        metrics.markFirstDeltaSent();
                    }
                }
            }

            if (control.isStopRequested()) {
                return;
            }

            if (!StringUtils.hasText(content.toString())) {
                status = MessageStatus.EMPTY_RESPONSE;
            } else if ("length".equalsIgnoreCase(finishReason)) {
                status = MessageStatus.OUTPUT_LIMIT;
            } else if (!"stop".equalsIgnoreCase(finishReason)) {
                status = MessageStatus.INCOMPLETE;
            } else {
                status = MessageStatus.SUCCESS;
            }

            error = errorFor(status);

        } catch (BusinessException exception) {
            status = MessageStatus.FAILED;
            error = new StreamError(
                    exception.errorCode().code(),
                    exception.errorCode().message()
            );

        } catch (IOException exception) {
            status = MessageStatus.OUTPUT_ERROR;
            error = errorFor(status);
            outputBroken = true;

        } catch (RuntimeException exception) {
            status = MessageStatus.FAILED;

            error = turn == null
                    ? new StreamError(
                    "CHAT_PREPARATION_FAILED",
                    "聊天准备失败，请稍后重试"
            )
                    : errorFor(status);

            // 不记录正文、Token 或供应商原始异常消息。
            log.warn(
                    "Chat execution failed: requestId={}, exceptionType={}",
                    requestId,
                    exception.getClass().getSimpleName()
            );

        } finally {
            // 与取消回调确定先后顺序；进入此阶段后，
            // 回调不再通过 control 中断当前线程。
            MessageStatus stopReason = control.beginFinalization();

            // 暂时清除已有中断标记，让数据库收尾有机会执行。
            // 最后恢复标记，不把取消信号永久吞掉。
            boolean restoreInterrupt = Thread.interrupted();

            if (stopReason != null) {
                status = stopReason;
                error = errorFor(status);
            }

            String metricStatus = status.name();

            try {
                boolean saved = false;
                boolean persistenceFailed = false;

                if (turn != null) {
                    try {
                        saved = finishService.finish(
                                turn,
                                status,
                                content.toString(),
                                finishReason,
                                error == null ? null : error.code()
                        );
                    } catch (RuntimeException exception) {
                        persistenceFailed = true;
                        metricStatus = "PERSISTENCE_FAILED";

                        error = new StreamError(
                                "CHAT_SAVE_FAILED",
                                "回答保存失败，请稍后查看会话记录确认"
                        );

                        // 不盲目重试，也不直接清空占用。
                        log.error(
                                "Chat finalization failed: requestId={}, "
                                        + "exceptionType={}",
                                requestId,
                                exception.getClass().getSimpleName()
                        );
                    }

                    if (!saved && !persistenceFailed) {
                        metricStatus = "STALE_REQUEST";

                        error = new StreamError(
                                "CHAT_REQUEST_INACTIVE",
                                "本次请求已结束或不再有效，请查看会话记录"
                        );
                    }
                }

                // 已取消或已发生输出错误时，不再发送终止事件。
                if (stopReason == null && !outputBroken) {
                    if (turn != null
                            && saved
                            && status == MessageStatus.SUCCESS) {
                        // 只有完整回答成功落库后才能发送 done。
                        send(emitter, "done", ++sequence,
                                new ChatStreamPayloads.Done(
                                        turn.assistantMessageId()
                                ));
                    } else {
                        StreamError terminalError = error != null
                                ? error
                                : errorFor(MessageStatus.FAILED);

                        send(emitter, "error", ++sequence,
                                new ChatStreamPayloads.Error(
                                        terminalError.code(),
                                        terminalError.message(),
                                        requestId
                                ));
                    }
                }

                if (!outputBroken) {
                    emitter.complete();
                }

            } catch (IOException | RuntimeException exception) {
                // 最终事件发送失败，不再次收尾，
                // 更不能把已经保存的完整回答覆盖成失败。
                if (!"PERSISTENCE_FAILED".equals(metricStatus)
                        && !"STALE_REQUEST".equals(metricStatus)) {
                    metricStatus = MessageStatus.OUTPUT_ERROR.name();
                }

                log.warn(
                        "Chat terminal output failed: requestId={}, "
                                + "exceptionType={}",
                        requestId,
                        exception.getClass().getSimpleName()
                );

            } finally {
                try {
                    if (metrics != null) {
                        log.info(
                                "chat_call_metrics={}",
                                metrics.snapshot(metricStatus, finishReason)
                        );
                    }
                } finally {
                    if (restoreInterrupt) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
    }

    /** 将内部结果状态转换为安全的对外错误信息。 */
    private StreamError errorFor(MessageStatus status) {
        return switch (status) {
            case SUCCESS -> null;

            case OUTPUT_LIMIT -> new StreamError(
                    "MODEL_OUTPUT_LIMIT",
                    "回答达到输出长度上限，内容可能不完整"
            );

            case EMPTY_RESPONSE -> new StreamError(
                    "MODEL_EMPTY_RESPONSE",
                    "模型未返回有效文本，请稍后重试"
            );

            case INCOMPLETE -> new StreamError(
                    "MODEL_RESPONSE_INCOMPLETE",
                    "模型回答未正常结束，内容可能不完整"
            );

            case TIMEOUT -> new StreamError(
                    "CHAT_TIMEOUT",
                    "本次请求超时，回答可能不完整"
            );

            case CANCELLED -> new StreamError(
                    "CHAT_CANCELLED",
                    "本次请求已取消"
            );

            case OUTPUT_ERROR -> new StreamError(
                    "CHAT_OUTPUT_ERROR",
                    "响应传输失败，回答可能不完整"
            );

            case INTERRUPTED -> new StreamError(
                    "CHAT_REQUEST_INTERRUPTED",
                    "本次请求异常中断"
            );

            default -> new StreamError(
                    "MODEL_STREAM_FAILED",
                    "模型调用失败，请稍后重试"
            );
        };
    }

    /** 按统一协议发送一个 SSE 事件。 */
    private <T> void send(
            SseEmitter emitter,
            String type,
            long sequence,
            T payload
    ) throws IOException {
        ChatStreamEvent<T> event =
                ChatStreamEvent.of(type, sequence, payload);

        emitter.send(
                SseEmitter.event()
                        .name(type)
                        .id(Long.toString(sequence))
                        .data(event, MediaType.APPLICATION_JSON)
        );
    }

    /** 仅用于当前 Controller 内部的安全错误信息。 */
    private record StreamError(
            String code,
            String message
    ) {
    }
}