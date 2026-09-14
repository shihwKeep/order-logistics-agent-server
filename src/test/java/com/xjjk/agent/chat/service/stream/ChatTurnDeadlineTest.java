package com.xjjk.agent.chat.service.stream;

import com.xjjk.agent.chat.domain.MessageStatus;
import com.xjjk.agent.chat.stream.ChatStreamControl;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatTurnDeadlineTest {

    @Test
    void stopsTheProducerAtItsAbsoluteDeadline() {
        ChatSseHeartbeatScheduler scheduler = mock(ChatSseHeartbeatScheduler.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        AtomicReference<Runnable> scheduled = new AtomicReference<>();
        when(scheduler.schedule(any(Runnable.class), eq(Duration.ofSeconds(30))))
                .thenAnswer(invocation -> {
                    scheduled.set(invocation.getArgument(0));
                    return future;
                });
        ChatStreamControl control = new ChatStreamControl();
        ChatTurnDeadline deadline = new ChatTurnDeadline(scheduler);

        AutoCloseable lease = deadline.schedule(
                Instant.parse("2026-09-14T08:00:30Z"),
                Instant.parse("2026-09-14T08:00:00Z"),
                control);
        scheduled.get().run();

        assertThat(control.beginFinalization()).isEqualTo(MessageStatus.TIMEOUT);
        org.assertj.core.api.Assertions.assertThatCode(lease::close)
                .doesNotThrowAnyException();
        verify(future).cancel(false);
    }
}
