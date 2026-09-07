package com.xjjk.agent.chat.service.summary;

import com.xjjk.agent.chat.config.ChatSummaryProperties;
import com.xjjk.agent.chat.domain.MessageRole;
import com.xjjk.agent.chat.domain.MessageStatus;
import com.xjjk.agent.chat.domain.summary.ChatSummaryCandidateBatch;
import com.xjjk.agent.chat.persistence.entity.AgentMessageEntity;
import com.xjjk.agent.chat.persistence.mapper.AgentMessageMapper;
import com.xjjk.agent.chat.persistence.projection.ChatSummaryMessageMetadata;
import com.xjjk.agent.chat.service.memory.QwenTextTokenEstimator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatSummaryCandidateLoaderTest {

    @Mock
    private AgentMessageMapper messageMapper;

    @Mock
    private QwenTextTokenEstimator tokenEstimator;

    private ChatSummaryCandidateLoader loader;

    @BeforeEach
    void setUp() {
        loader = new ChatSummaryCandidateLoader(
                messageMapper,
                properties(),
                tokenEstimator
        );
    }

    @Test
    void shouldLoadOldestContiguousTurnsAndRetainNewestTurn() {
        when(tokenEstimator.estimate(anyString())).thenReturn(10L);
        when(messageMapper.selectRecentSummaryMetadata(
                1, 10567, "conversation-1", 6, 2
        )).thenReturn(List.of(
                metadata(6, "r3", MessageRole.ASSISTANT,
                        MessageStatus.SUCCESS, 10),
                metadata(5, "r3", MessageRole.USER,
                        MessageStatus.SUCCESS, 10)
        ));
        when(messageMapper.selectSummaryMetadata(
                1, 10567, "conversation-1", 0, 4, 11
        )).thenReturn(List.of(
                metadata(1, "r1", MessageRole.USER,
                        MessageStatus.SUCCESS, 9),
                metadata(2, "r1", MessageRole.ASSISTANT,
                        MessageStatus.SUCCESS, 9),
                metadata(3, "r2", MessageRole.USER,
                        MessageStatus.SUCCESS, 9),
                metadata(4, "r2", MessageRole.ASSISTANT,
                        MessageStatus.TIMEOUT, 15)
        ));
        when(messageMapper.selectSummaryBodies(
                1, 10567, "conversation-1", 1, 4
        )).thenReturn(List.of(
                message(1, "r1", MessageRole.USER,
                        MessageStatus.SUCCESS, "问题一"),
                message(2, "r1", MessageRole.ASSISTANT,
                        MessageStatus.SUCCESS, "回答一"),
                message(3, "r2", MessageRole.USER,
                        MessageStatus.SUCCESS, "问题二"),
                message(4, "r2", MessageRole.ASSISTANT,
                        MessageStatus.TIMEOUT, "不完整回答")
        ));

        ChatSummaryCandidateBatch batch = loader.load(
                1, 10567, "conversation-1", 0, 3, 6
        );

        assertThat(batch.turns()).hasSize(2);
        assertThat(batch.selectedFromSequence()).isEqualTo(1);
        assertThat(batch.selectedUntilSequence()).isEqualTo(4);
        assertThat(batch.turns().get(0).assistantContent())
                .isEqualTo("回答一");
        assertThat(batch.turns().get(1).assistantStatus())
                .isEqualTo(MessageStatus.TIMEOUT);
        assertThat(batch.turns().get(1).assistantContent()).isNull();
        assertThat(batch.hasMoreEligibleMessages()).isFalse();
    }

    @Test
    void shouldFailClosedWhenPairRolesAreWrong() {
        when(messageMapper.selectRecentSummaryMetadata(
                1, 10567, "conversation-1", 6, 2
        )).thenReturn(List.of(
                metadata(6, "r3", MessageRole.ASSISTANT,
                        MessageStatus.SUCCESS, 10),
                metadata(5, "r3", MessageRole.USER,
                        MessageStatus.SUCCESS, 10)
        ));
        when(messageMapper.selectSummaryMetadata(
                1, 10567, "conversation-1", 0, 4, 11
        )).thenReturn(List.of(
                metadata(1, "r1", MessageRole.ASSISTANT,
                        MessageStatus.SUCCESS, 10),
                metadata(2, "r1", MessageRole.USER,
                        MessageStatus.SUCCESS, 10)
        ));

        assertThatIllegalStateException().isThrownBy(() -> loader.load(
                1, 10567, "conversation-1", 0, 3, 6
        ));
    }

    @Test
    void shouldFailClosedWhenRecentWindowDoesNotReachCapturedBoundary() {
        when(messageMapper.selectRecentSummaryMetadata(
                1, 10567, "conversation-1", 6, 2
        )).thenReturn(List.of(
                metadata(5, "r2", MessageRole.ASSISTANT,
                        MessageStatus.SUCCESS, 10),
                metadata(4, "r2", MessageRole.USER,
                        MessageStatus.SUCCESS, 10)
        ));

        assertThatIllegalStateException().isThrownBy(() -> loader.load(
                1, 10567, "conversation-1", 0, 3, 6
        )).withMessageContaining("捕获边界");
    }

    @Test
    void shouldFailClosedWhenCandidateRangeStopsBeforeEligibleBoundary() {
        when(messageMapper.selectRecentSummaryMetadata(
                1, 10567, "conversation-1", 6, 2
        )).thenReturn(List.of(
                metadata(6, "r3", MessageRole.ASSISTANT,
                        MessageStatus.SUCCESS, 10),
                metadata(5, "r3", MessageRole.USER,
                        MessageStatus.SUCCESS, 10)
        ));
        when(messageMapper.selectSummaryMetadata(
                1, 10567, "conversation-1", 0, 4, 11
        )).thenReturn(List.of(
                metadata(1, "r1", MessageRole.USER,
                        MessageStatus.SUCCESS, 10),
                metadata(2, "r1", MessageRole.ASSISTANT,
                        MessageStatus.SUCCESS, 10)
        ));

        assertThatIllegalStateException().isThrownBy(() -> loader.load(
                1, 10567, "conversation-1", 0, 3, 6
        )).withMessageContaining("完整覆盖");
    }

    @Test
    void shouldRejectGeneratingAssistantInRetainedWindow() {
        when(messageMapper.selectRecentSummaryMetadata(
                1, 10567, "conversation-1", 6, 2
        )).thenReturn(List.of(
                metadata(6, "r3", MessageRole.ASSISTANT,
                        MessageStatus.GENERATING, 10),
                metadata(5, "r3", MessageRole.USER,
                        MessageStatus.SUCCESS, 10)
        ));

        assertThatIllegalStateException().isThrownBy(() -> loader.load(
                1, 10567, "conversation-1", 0, 3, 6
        )).withMessageContaining("终态");
    }

    @Test
    void shouldValidateShortRecentWindowBeforeReturningNoCandidate() {
        loader = new ChatSummaryCandidateLoader(
                messageMapper,
                properties(2),
                tokenEstimator
        );
        when(messageMapper.selectRecentSummaryMetadata(
                1, 10567, "conversation-1", 2, 4
        )).thenReturn(List.of(
                metadata(2, "r1", MessageRole.ASSISTANT,
                        MessageStatus.GENERATING, 10),
                metadata(1, "r1", MessageRole.USER,
                        MessageStatus.SUCCESS, 10)
        ));

        assertThatIllegalStateException().isThrownBy(() -> loader.load(
                1, 10567, "conversation-1", 0, 1, 2
        )).withMessageContaining("终态");
    }

    private static ChatSummaryProperties properties() {
        return properties(1);
    }

    private static ChatSummaryProperties properties(int retainRecentTurns) {
        return new ChatSummaryProperties(
                true, false, false, 1,
                100, 10, retainRecentTurns, 100,
                10, 1_000, 100,
                20, 30, 20,
                "summary-v1", "qwen-plus", 0.1,
                Duration.ofSeconds(10),
                new ChatSummaryProperties.Worker(
                        1, 1, 10, 2,
                        Duration.ofSeconds(5),
                        Duration.ofMinutes(1),
                        Duration.ofSeconds(30),
                        "agent-1"
                ),
                new ChatSummaryProperties.Retry(
                        3, Duration.ofSeconds(1),
                        Duration.ofSeconds(10), Duration.ZERO
                )
        );
    }

    private static ChatSummaryMessageMetadata metadata(
            long sequence,
            String requestId,
            MessageRole role,
            MessageStatus status,
            long bytes
    ) {
        ChatSummaryMessageMetadata row =
                new ChatSummaryMessageMetadata();
        row.setMessageId("m" + sequence);
        row.setRequestId(requestId);
        row.setMessageSequence(sequence);
        row.setRole(role.name());
        row.setStatus(status.name());
        row.setContentBytes(bytes);
        return row;
    }

    private static AgentMessageEntity message(
            long sequence,
            String requestId,
            MessageRole role,
            MessageStatus status,
            String content
    ) {
        AgentMessageEntity message = new AgentMessageEntity();
        message.setMessageId("m" + sequence);
        message.setRequestId(requestId);
        message.setMessageSequence(sequence);
        message.setRole(role.name());
        message.setStatus(status.name());
        message.setContent(content);
        return message;
    }
}
