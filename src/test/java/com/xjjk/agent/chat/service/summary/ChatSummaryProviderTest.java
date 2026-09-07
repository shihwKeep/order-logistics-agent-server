package com.xjjk.agent.chat.service.summary;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xjjk.agent.chat.config.ChatSummaryProperties;
import com.xjjk.agent.chat.domain.memory.ChatHistoryCursor;
import com.xjjk.agent.chat.persistence.entity.AgentConversationSummaryEntity;
import com.xjjk.agent.chat.persistence.mapper.AgentConversationSummaryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatSummaryProviderTest {

    @Mock
    private AgentConversationSummaryMapper mapper;

    private ChatHistoryCursor cursor;

    @BeforeEach
    void setUp() {
        cursor = new ChatHistoryCursor(
                1L, 10567L, "conversation-1", 8L, 16L, 17L
        );
    }

    @Test
    void contextDisabledDoesNotReadSummaryTable() {
        ChatSummaryProvider provider = provider(false);

        assertThat(provider.load(cursor).present()).isFalse();
        verifyNoInteractions(mapper);
    }

    @Test
    void absentSummaryReturnsCursorScopedEmptySnapshot() {
        when(mapper.selectOwned(1L, 10567L, "conversation-1"))
                .thenReturn(null);

        assertThat(provider(true).load(cursor))
                .satisfies(snapshot -> {
                    assertThat(snapshot.present()).isFalse();
                    assertThat(snapshot.tenantId()).isEqualTo(1L);
                    assertThat(snapshot.userId()).isEqualTo(10567L);
                    assertThat(snapshot.stableMemoryUntilSequence())
                            .isEqualTo(16L);
                });
    }

    @Test
    void corruptOrUnsupportedSummaryFailsOpen() {
        AgentConversationSummaryEntity corrupt = entity();
        corrupt.setContentJson("{not-json");
        when(mapper.selectOwned(1L, 10567L, "conversation-1"))
                .thenReturn(corrupt);

        assertThat(provider(true).load(cursor).present()).isFalse();

        AgentConversationSummaryEntity unsupported = entity();
        unsupported.setSchemaVersion(2);
        when(mapper.selectOwned(1L, 10567L, "conversation-1"))
                .thenReturn(unsupported);

        assertThat(provider(true).load(cursor).present()).isFalse();
    }

    @Test
    void coverageAboveStableBoundaryIsRejectedFailOpen() {
        AgentConversationSummaryEntity entity = entity();
        entity.setCoveredUntilSequence(18L);
        when(mapper.selectOwned(1L, 10567L, "conversation-1"))
                .thenReturn(entity);

        assertThat(provider(true).load(cursor).present()).isFalse();
    }

    @Test
    void mismatchedOwnershipFailsClosed() {
        AgentConversationSummaryEntity entity = entity();
        entity.setTenantId(2L);
        when(mapper.selectOwned(1L, 10567L, "conversation-1"))
                .thenReturn(entity);

        assertThatThrownBy(() -> provider(true).load(cursor))
                .isInstanceOf(SecurityException.class);
        verify(mapper).selectOwned(1L, 10567L, "conversation-1");
    }

    @Test
    void securityFailureFromOwnedQueryIsNeverDowngraded() {
        when(mapper.selectOwned(1L, 10567L, "conversation-1"))
                .thenThrow(new SecurityException("tenant interceptor"));

        assertThatThrownBy(() -> provider(true).load(cursor))
                .isInstanceOf(SecurityException.class);
    }

    private ChatSummaryProvider provider(boolean contextEnabled) {
        return new ChatSummaryProvider(
                mapper,
                new ObjectMapper(),
                properties(contextEnabled)
        );
    }

    private AgentConversationSummaryEntity entity() {
        AgentConversationSummaryEntity entity =
                new AgentConversationSummaryEntity();
        entity.setTenantId(1L);
        entity.setUserId(10567L);
        entity.setConversationId("conversation-1");
        entity.setSummaryVersion(3L);
        entity.setCoveredUntilSequence(8L);
        entity.setSourceMemoryVersion(4L);
        entity.setSchemaVersion(1);
        entity.setContentJson("""
                {"schemaVersion":1,"topic":"物流查询",
                 "currentState":"等待运单号",
                 "conversationFacts":[],"decisions":[],
                 "openQuestions":[],"importantEntities":[]}
                """);
        entity.setPromptVersion("summary-v1");
        entity.setModelName("qwen-plus");
        return entity;
    }

    private static ChatSummaryProperties properties(
            boolean contextEnabled
    ) {
        return new ChatSummaryProperties(
                true, false, contextEnabled, 1,
                100, 2, 1, 100,
                10, 1_000, 500,
                20, 30, 20,
                "summary-v1", "qwen-plus", 0.1,
                Duration.ofSeconds(10),
                new ChatSummaryProperties.Worker(
                        1, 1, 10, 2,
                        Duration.ofSeconds(5),
                        Duration.ofMinutes(1),
                        Duration.ofSeconds(30), "agent-1"
                ),
                new ChatSummaryProperties.Retry(
                        3, Duration.ofSeconds(1),
                        Duration.ofSeconds(10), Duration.ZERO
                )
        );
    }
}
