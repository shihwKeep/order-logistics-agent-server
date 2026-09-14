package com.xjjk.agent.chat.service.stream;

import com.xjjk.agent.chat.api.dto.ChatStreamStatusResponse;
import com.xjjk.agent.chat.config.ChatStreamProperties;
import com.xjjk.agent.chat.observation.ChatStreamReplayMetrics;
import com.xjjk.agent.chat.replay.ChatReplayRepository;
import com.xjjk.agent.chat.replay.ChatReplaySnapshot;
import com.xjjk.agent.common.api.ApiErrorCode;
import com.xjjk.agent.common.exception.BusinessException;
import com.xjjk.agent.identity.domain.AgentIdentity;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Objects;
import java.util.UUID;

/** 建立可恢复 SSE 消费连接并查询 Redis 中的任务状态。 */
@Service
public class ChatSseRelayService {

    private final ChatReplayRepository repository;
    private final ChatStreamProperties properties;
    private final TaskExecutor relayExecutor;
    private final ChatStreamReplayMetrics metrics;

    public ChatSseRelayService(
            ChatReplayRepository repository,
            ChatStreamProperties properties,
            @Qualifier("chatSseRelayExecutor") TaskExecutor relayExecutor,
            ChatStreamReplayMetrics metrics
    ) {
        this.repository = Objects.requireNonNull(repository, "回放仓储不能为空");
        this.properties = Objects.requireNonNull(properties, "聊天流配置不能为空");
        this.relayExecutor = Objects.requireNonNull(relayExecutor, "SSE 中继线程池不能为空");
        this.metrics = Objects.requireNonNull(metrics, "聊天流指标不能为空");
    }

    public SseEmitter resume(
            AgentIdentity identity,
            String requestId,
            long afterSequence
    ) {
        validateRequest(requestId, afterSequence);
        ChatReplaySnapshot snapshot = ownedSnapshot(identity, requestId);
        String connectionId = UUID.randomUUID().toString();
        if (!repository.activateConnection(identity, requestId, connectionId)) {
            metrics.resume("rejected");
            throw new BusinessException(ApiErrorCode.CHAT_STREAM_NOT_FOUND);
        }
        metrics.resume("success");
        AutoCloseable relayMetric = metrics.relayConnection();
        SseEmitter emitter = new SseEmitter(properties.timeout().toMillis());
        ChatSseRelay relay = new ChatSseRelay(
                repository,
                identity,
                requestId,
                afterSequence,
                connectionId,
                properties.replay().readBlockTimeout(),
                emitter);

        // 回调只控制该中继，绝不写取消标志，也不触碰 Agent Job。
        Runnable closeRelay = () -> {
            relay.disconnect();
            closeMetric(relayMetric);
        };
        emitter.onCompletion(closeRelay);
        emitter.onError(ignored -> closeRelay.run());
        emitter.onTimeout(() -> {
            closeRelay.run();
            emitter.complete();
        });
        try {
            relayExecutor.execute(relay);
        } catch (RuntimeException exception) {
            closeRelay.run();
            metrics.resume("failure");
            throw exception;
        }
        return emitter;
    }

    private void closeMetric(AutoCloseable metric) {
        try {
            metric.close();
        } catch (Exception exception) {
            throw new IllegalStateException("关闭 SSE 中继指标失败", exception);
        }
    }

    public ChatStreamStatusResponse status(AgentIdentity identity, String requestId) {
        validateRequest(requestId, 0L);
        return ChatStreamStatusResponse.from(ownedSnapshot(identity, requestId));
    }

    private ChatReplaySnapshot ownedSnapshot(AgentIdentity identity, String requestId) {
        Objects.requireNonNull(identity, "认证身份不能为空");
        return repository.status(identity, requestId)
                .orElseThrow(() -> new BusinessException(ApiErrorCode.CHAT_STREAM_NOT_FOUND));
    }

    private void validateRequest(String requestId, long afterSequence) {
        if (afterSequence < 0) {
            throw new BusinessException(ApiErrorCode.VALIDATION_ERROR);
        }
        try {
            if (!UUID.fromString(requestId).toString().equalsIgnoreCase(requestId)) {
                throw new IllegalArgumentException("非规范 UUID");
            }
        } catch (RuntimeException exception) {
            throw new BusinessException(ApiErrorCode.VALIDATION_ERROR);
        }
    }
}
