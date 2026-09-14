package com.xjjk.agent.chat.service.stream;

import com.xjjk.agent.chat.replay.ChatReplayRepository;
import com.xjjk.agent.identity.domain.AgentIdentity;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatReplayCancellationProbeTest {

    private static final AgentIdentity IDENTITY = new AgentIdentity(
            2L, "agent", "坐席", 3L, 1L);
    private static final String REQUEST_ID =
            "6f899318-0af5-4f2b-a593-84f6dac9dd1c";

    @Test
    void throttlesRedisReadsAndKeepsACancellationSticky() {
        ChatReplayRepository repository = mock(ChatReplayRepository.class);
        when(repository.cancellationRequested(IDENTITY, REQUEST_ID))
                .thenReturn(false, true);
        AtomicLong ticker = new AtomicLong();
        ChatReplayCancellationProbe probe = new ChatReplayCancellationProbe(
                repository, IDENTITY, REQUEST_ID,
                Duration.ofMillis(250), ticker::get);

        assertThat(probe.getAsBoolean()).isFalse();
        ticker.set(Duration.ofMillis(100).toNanos());
        assertThat(probe.getAsBoolean()).isFalse();
        verify(repository, times(1)).cancellationRequested(IDENTITY, REQUEST_ID);

        ticker.set(Duration.ofMillis(251).toNanos());
        assertThat(probe.getAsBoolean()).isTrue();
        ticker.set(Duration.ofSeconds(1).toNanos());
        assertThat(probe.getAsBoolean()).isTrue();
        verify(repository, times(2)).cancellationRequested(IDENTITY, REQUEST_ID);
    }
}
