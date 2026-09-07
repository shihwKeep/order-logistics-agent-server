package com.xjjk.agent.chat.service.memory;

import com.xjjk.agent.chat.cache.ChatHistorySnapshotCache;
import com.xjjk.agent.chat.domain.ChatTurnContext;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatHistorySnapshotProviderTest {

    @Mock
    private ChatHistoryCursorLoader cursorLoader;

    @Mock
    private ChatHistorySnapshotCache cache;

    @Mock
    private ChatHistoryLoader historyLoader;

    private ChatHistorySnapshotProvider provider;
    private ChatTurnContext turn;
    private ChatHistoryCursor cursor;
    private ChatHistorySnapshot snapshot;

    @BeforeEach
    void setUp() {
        provider = new ChatHistorySnapshotProvider(
                cursorLoader,
                cache,
                historyLoader,
                new ChatHistoryCacheMetrics(
                        new SimpleMeterRegistry())
        );
        turn = new ChatTurnContext(
                1,
                10567,
                "conversation-1",
                "request-2",
                "user-message-2",
                "assistant-message-2",
                "prompt-v2"
        );
        cursor = new ChatHistoryCursor(
                1, 10567, "conversation-1", 8, 16, 17);
        snapshot = new ChatHistorySnapshot(
                1,
                10567,
                "conversation-1",
                8,
                16,
                17,
                List.of(),
                false,
                false
        );
    }

    @Test
    void hitSkipsDatabaseHistoryBodyLoad() {
        when(cursorLoader.loadForRequest(turn)).thenReturn(cursor);
        when(cache.get(cursor)).thenReturn(Optional.of(snapshot));

        assertThat(provider.load(turn)).isSameAs(snapshot);
        verifyNoInteractions(historyLoader);
    }

    @Test
    void missLoadsDatabaseAndPopulatesCache() {
        when(cursorLoader.loadForRequest(turn)).thenReturn(cursor);
        when(cache.get(cursor)).thenReturn(Optional.empty());
        when(historyLoader.load(cursor)).thenReturn(snapshot);

        assertThat(provider.load(turn)).isSameAs(snapshot);
        verify(cache).put(cursor, snapshot);
    }
}
