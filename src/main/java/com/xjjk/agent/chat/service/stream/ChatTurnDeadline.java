package com.xjjk.agent.chat.service.stream;

import com.xjjk.agent.chat.domain.MessageStatus;
import com.xjjk.agent.chat.stream.ChatStreamControl;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;

/** 以服务端首次受理时间为基准限制整个生产任务，不因 SSE 重连而延期。 */
@Component
public final class ChatTurnDeadline {

    private final ChatSseHeartbeatScheduler scheduler;

    public ChatTurnDeadline(ChatSseHeartbeatScheduler scheduler) {
        this.scheduler = Objects.requireNonNull(scheduler, "聊天调度器不能为空");
    }

    public AutoCloseable schedule(
            Instant expiresAt,
            Instant now,
            ChatStreamControl control
    ) {
        Objects.requireNonNull(expiresAt, "任务截止时间不能为空");
        Objects.requireNonNull(now, "当前时间不能为空");
        Objects.requireNonNull(control, "任务控制对象不能为空");
        Duration delay = Duration.between(now, expiresAt);
        if (delay.isNegative()) {
            delay = Duration.ZERO;
        }
        ScheduledFuture<?> future = scheduler.schedule(
                () -> control.requestStop(MessageStatus.TIMEOUT), delay);
        return () -> future.cancel(false);
    }
}
