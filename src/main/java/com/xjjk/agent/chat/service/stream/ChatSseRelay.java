package com.xjjk.agent.chat.service.stream;

import com.xjjk.agent.chat.api.dto.ChatStreamEvent;
import com.xjjk.agent.chat.replay.ChatReplayEvent;
import com.xjjk.agent.chat.replay.ChatReplayRepository;
import com.xjjk.agent.identity.domain.AgentIdentity;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单条 Redis Streams 到 SSE 连接的转发任务。
 *
 * <p>它只消费回放事件，不持有也不取消 Agent 生产任务。</p>
 */
public final class ChatSseRelay implements Runnable {

    private final ChatReplayRepository repository;
    private final AgentIdentity identity;
    private final String requestId;
    private final String connectionId;
    private final Duration blockTimeout;
    private final SseEmitter emitter;
    private final AtomicBoolean disconnected = new AtomicBoolean();
    private long lastSequence;
    private volatile Thread relayThread;

    public ChatSseRelay(
            ChatReplayRepository repository,
            AgentIdentity identity,
            String requestId,
            long afterSequence,
            String connectionId,
            Duration blockTimeout,
            SseEmitter emitter
    ) {
        if (afterSequence < 0) {
            throw new IllegalArgumentException("已确认事件序号不能小于零");
        }
        this.repository = Objects.requireNonNull(repository, "回放仓储不能为空");
        this.identity = Objects.requireNonNull(identity, "认证身份不能为空");
        this.requestId = requireText(requestId, "请求 ID 不能为空");
        this.connectionId = requireText(connectionId, "连接 ID 不能为空");
        this.blockTimeout = Objects.requireNonNull(blockTimeout, "阻塞读取时间不能为空");
        this.emitter = Objects.requireNonNull(emitter, "SSE 输出不能为空");
        this.lastSequence = afterSequence;
    }

    @Override
    public void run() {
        if (disconnected.get()) {
            return;
        }
        relayThread = Thread.currentThread();
        try {
            while (!disconnected.get()) {
                if (!ownsActiveConnection()) {
                    disconnected.set(true);
                    return;
                }
                List<ChatReplayEvent> events = repository.readAfter(
                        identity, requestId, lastSequence, blockTimeout);
                if (!ownsActiveConnection()) {
                    disconnected.set(true);
                    return;
                }
                for (ChatReplayEvent event : events) {
                    if (disconnected.get()) {
                        return;
                    }
                    if (event.sequence() <= lastSequence) {
                        continue;
                    }
                    send(event);
                    lastSequence = event.sequence();
                    if (terminal(event.type())) {
                        disconnected.set(true);
                        emitter.complete();
                        return;
                    }
                }
            }
        } catch (IOException exception) {
            // 网络输出失败只关闭本中继；生产任务继续写 Redis，等待客户端恢复。
            disconnected.set(true);
        } catch (RuntimeException exception) {
            disconnected.set(true);
            if (!Thread.currentThread().isInterrupted()) {
                emitter.completeWithError(exception);
            }
        } finally {
            relayThread = null;
        }
    }

    /** 仅停止当前消费者，不写 Redis 取消标志。 */
    public void disconnect() {
        disconnected.set(true);
        Thread thread = relayThread;
        if (thread != null) {
            thread.interrupt();
        }
    }

    public String connectionId() {
        return connectionId;
    }

    private boolean ownsActiveConnection() {
        return repository.isActiveConnection(identity, requestId, connectionId);
    }

    private void send(ChatReplayEvent event) throws IOException {
        emitter.send(SseEmitter.event()
                .name(event.type())
                .id(Long.toString(event.sequence()))
                .data(new ChatStreamEvent<>(
                                event.type(), event.sequence(), event.timestamp(), event.payload()),
                        MediaType.APPLICATION_JSON));
    }

    private boolean terminal(String type) {
        return "done".equals(type) || "error".equals(type);
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
