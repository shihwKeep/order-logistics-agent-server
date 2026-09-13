package com.xjjk.agent.chat.service.stream;

import com.xjjk.agent.chat.config.ChatStreamProperties;
import com.xjjk.agent.chat.domain.MessageStatus;
import com.xjjk.agent.chat.stream.ChatSseSession;
import com.xjjk.agent.chat.stream.ChatStreamControl;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatSseHeartbeatTest {

    @Test
    void sendsHeartbeatAtFixedRateAndCloseCancelsTheSchedule() throws Exception {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        AtomicReference<Runnable> scheduled = captureScheduledTask(scheduler, future);
        ChatSseSession session = mock(ChatSseSession.class);
        ChatStreamControl control = new ChatStreamControl();
        ChatSseHeartbeat heartbeat = new ChatSseHeartbeat(scheduler, properties());

        ChatSseHeartbeat.Lease lease = heartbeat.start(session, control);
        scheduled.get().run();
        lease.close();

        verify(session).heartbeat();
        verify(future).cancel(false);
    }

    @Test
    void sendFailureStopsTheTurnAndCancelsFutureTicks() throws Exception {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        AtomicReference<Runnable> scheduled = captureScheduledTask(scheduler, future);
        ChatSseSession session = mock(ChatSseSession.class);
        doThrow(new IOException("connection closed")).when(session).heartbeat();
        ChatStreamControl control = new ChatStreamControl();
        FutureTask<Void> turnTask = new FutureTask<>(() -> null);
        control.bind(turnTask);
        ChatSseHeartbeat heartbeat = new ChatSseHeartbeat(scheduler, properties());

        heartbeat.start(session, control);
        scheduled.get().run();

        assertThat(turnTask.isCancelled()).isTrue();
        assertThat(control.beginFinalization()).isEqualTo(MessageStatus.OUTPUT_ERROR);
        verify(future).cancel(false);
    }

    @Test
    void requiresHeartbeatIntervalToBeShorterThanTotalTimeout() {
        assertThatThrownBy(() -> new ChatStreamProperties(
                Duration.ofSeconds(30), Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("心跳间隔");
    }

    private AtomicReference<Runnable> captureScheduledTask(
            TaskScheduler scheduler,
            ScheduledFuture<?> future
    ) {
        AtomicReference<Runnable> scheduled = new AtomicReference<>();
        when(scheduler.scheduleAtFixedRate(any(Runnable.class), eq(Duration.ofSeconds(10))))
                .thenAnswer(invocation -> {
                    scheduled.set(invocation.getArgument(0));
                    return future;
                });
        return scheduled;
    }

    private ChatStreamProperties properties() {
        return new ChatStreamProperties(Duration.ofSeconds(30), Duration.ofSeconds(10));
    }
}
