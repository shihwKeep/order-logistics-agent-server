package com.xjjk.agent.chat.orchestration;

import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LangGraph4jRedisCheckpointSaverTest {

    private final CompositeQueryCheckpointStore store = mock(CompositeQueryCheckpointStore.class);
    private LangGraph4jRedisCheckpointSaver saver;

    @BeforeEach
    void setUp() {
        saver = new LangGraph4jRedisCheckpointSaver(
                store, "v2", Clock.fixed(Instant.parse("2026-10-03T10:00:00Z"),
                        ZoneOffset.UTC));
    }

    @Test
    void mapsLangGraphCheckpointToSafeRedisCheckpoint() throws Exception {
        RunnableConfig config = RunnableConfig.builder().threadId("req-1").build();
        Checkpoint checkpoint = Checkpoint.builder()
                .id("cp-1")
                .state(Map.of(
                        "requestId", "req-1",
                        "conversationId", "conversation-1",
                        "planHash", "hash-1",
                        "finalStatus", "PENDING",
                        "userMessage", "不要写入 checkpoint"))
                .nodeId("business.query")
                .nextNodeId("result.validate")
                .build();

        saver.put(config, checkpoint);

        ArgumentCaptor<CompositeQueryCheckpoint> captor =
                ArgumentCaptor.forClass(CompositeQueryCheckpoint.class);
        verify(store).save(captor.capture());
        CompositeQueryCheckpoint saved = captor.getValue();
        assertThat(saved.threadId()).isEqualTo("req-1");
        assertThat(saved.stateJson()).containsEntry("finalStatus", "PENDING")
                .doesNotContainKey("userMessage");
        assertThat(saved.completedNodes()).containsExactly("business.query");
        assertThat(saved.pendingNodes()).containsExactly("result.validate");
    }

    @Test
    void restoresLatestCheckpointForRunnableConfig() {
        RunnableConfig config = RunnableConfig.builder().threadId("req-1").build();
        CompositeQueryCheckpoint saved = new CompositeQueryCheckpoint(
                "v2", "req-1", "req-1", "conversation-1", "hash-1",
                Map.of("finalStatus", "PENDING"),
                List.of("business.query"), List.of("result.validate"),
                Map.of(), "result.validate", "PENDING", "2026-10-03T10:00:00Z");
        when(store.load("req-1")).thenReturn(Optional.of(saved));

        assertThat(saver.get(config)).isPresent()
                .get().extracting(Checkpoint::getNodeId).isEqualTo("business.query");
    }
}
