package com.xjjk.agent.chat.service.memory;

import com.xjjk.agent.chat.cache.ChatHistorySnapshotCache;
import com.xjjk.agent.chat.domain.memory.ChatHistoryCursor;
import com.xjjk.agent.chat.domain.memory.ChatHistorySnapshot;
import com.xjjk.agent.chat.observation.ChatHistoryCacheMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatHistoryCacheWarmServiceTest {

    @Mock
    private ChatHistoryCursorLoader cursorLoader;

    @Mock
    private ChatHistoryLoader historyLoader;

    @Mock
    private ChatHistorySnapshotCache cache;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private ChatHistoryCacheWarmService service;
    private ChatHistoryChangedEvent event;
    private ChatHistoryCursor cursor;
    private ChatHistorySnapshot snapshot;

    @BeforeEach
    void setUp() {
        service = new ChatHistoryCacheWarmService(
                cursorLoader,
                historyLoader,
                cache,
                new ChatHistoryCacheMetrics(registry)
        );
        event = new ChatHistoryChangedEvent(
                1, 10567, "conversation-1", 9, 18);
        cursor = new ChatHistoryCursor(
                1, 10567, "conversation-1", 9, 18, 19);
        snapshot = new ChatHistorySnapshot(
                1,
                10567,
                "conversation-1",
                9,
                18,
                19,
                List.of(),
                false,
                false
        );
    }

    @Test
    void matchingEventWarmsVersionedCache() {
        when(cursorLoader.loadForWarm(event))
                .thenReturn(Optional.of(cursor));
        when(historyLoader.load(cursor)).thenReturn(snapshot);

        service.warm(event);

        verify(cache).put(cursor, snapshot);
        assertThat(counter("chat.history.cache.warm.success"))
                .isEqualTo(1.0);
    }

    @Test
    void staleEventIsSkipped() {
        when(cursorLoader.loadForWarm(event))
                .thenReturn(Optional.empty());

        service.warm(event);

        verifyNoInteractions(historyLoader, cache);
        assertThat(counter("chat.history.cache.warm.skipped"))
                .isEqualTo(1.0);
    }

    @Test
    void warmFailureDoesNotEscapeExecutorThread() {
        when(cursorLoader.loadForWarm(event))
                .thenThrow(new IllegalStateException("database down"));

        assertThatCode(() -> service.warm(event))
                .doesNotThrowAnyException();
        assertThat(counter("chat.history.cache.warm.error"))
                .isEqualTo(1.0);
    }

    private double counter(String name) {
        return registry.get(name).counter().count();
    }
}
