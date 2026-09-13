package com.xjjk.agent.chat.service.stream;

import com.xjjk.agent.chat.config.ChatStreamProperties;
import com.xjjk.agent.chat.domain.MessageStatus;
import com.xjjk.agent.chat.stream.ChatSseSession;
import com.xjjk.agent.chat.stream.ChatStreamControl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;

/**
 * 为单条聊天流创建轻量心跳任务。
 * 心跳写出失败等价于输出通道失败，并通过同一控制对象中止下游生成。
 */
@Component
public final class ChatSseHeartbeat {

    private static final Logger log = LoggerFactory.getLogger(ChatSseHeartbeat.class);

    private final TaskScheduler scheduler;
    private final ChatStreamProperties properties;

    public ChatSseHeartbeat(
            @Qualifier("chatSseHeartbeatScheduler") TaskScheduler scheduler,
            ChatStreamProperties properties
    ) {
        this.scheduler = Objects.requireNonNull(scheduler, "心跳调度器不能为空");
        this.properties = Objects.requireNonNull(properties, "聊天流配置不能为空");
    }

    public Lease start(ChatSseSession session, ChatStreamControl control) {
        Objects.requireNonNull(session, "SSE 会话不能为空");
        Objects.requireNonNull(control, "聊天流控制对象不能为空");

        Lease lease = new Lease();
        Runnable tick = () -> {
            if (lease.isClosed()) {
                return;
            }
            try {
                session.heartbeat();
            } catch (IOException exception) {
                log.debug("Chat SSE heartbeat write failed; stopping stream", exception);
                control.requestStop(MessageStatus.OUTPUT_ERROR);
                lease.close();
            }
        };
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                tick,
                properties.heartbeatInterval()
        );
        if (future == null) {
            throw new IllegalStateException("无法创建聊天流心跳任务");
        }
        lease.bind(future);
        return lease;
    }

    /** 可跨回调重复关闭的心跳租约。 */
    public static final class Lease implements AutoCloseable {

        private ScheduledFuture<?> future;
        private boolean closed;

        private synchronized void bind(ScheduledFuture<?> future) {
            if (this.future != null) {
                throw new IllegalStateException("心跳任务不能重复绑定");
            }
            this.future = Objects.requireNonNull(future, "心跳任务不能为空");
            if (closed) {
                future.cancel(false);
            }
        }

        private synchronized boolean isClosed() {
            return closed;
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (future != null) {
                future.cancel(false);
            }
        }
    }
}
