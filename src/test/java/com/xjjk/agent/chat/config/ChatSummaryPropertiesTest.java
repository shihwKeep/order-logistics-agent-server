package com.xjjk.agent.chat.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ChatSummaryPropertiesTest {

    @Test
    void shouldRejectOutputTargetAboveMaximum() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> validProperties(1_025, 1_024));
    }

    @Test
    void shouldRejectLeaseThatCannotCoverModelTimeout() {
        ChatSummaryProperties.Worker worker = new ChatSummaryProperties.Worker(
                1, 2, 100, 10,
                Duration.ofSeconds(5),
                Duration.ofMinutes(1),
                Duration.ofSeconds(15),
                "agent-1"
        );

        assertThatIllegalArgumentException()
                .isThrownBy(() -> validProperties(
                        768,
                        1_024,
                        worker,
                        validRetry()
                ));
    }

    @Test
    void shouldRejectLeaseThatCannotCoverCorrectiveRetryAndCommitMargin() {
        ChatSummaryProperties.Worker worker = new ChatSummaryProperties.Worker(
                1, 2, 100, 10,
                Duration.ofSeconds(5),
                Duration.ofMinutes(1),
                Duration.ofSeconds(20),
                "agent-1"
        );

        assertThatIllegalArgumentException()
                .isThrownBy(() -> validProperties(
                        768,
                        1_024,
                        worker,
                        validRetry()
                ));
    }

    @Test
    void shouldRejectInvalidRetryRange() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ChatSummaryProperties.Retry(
                        5,
                        Duration.ofMinutes(11),
                        Duration.ofMinutes(10),
                        Duration.ofSeconds(5)
                ));
    }

    @Test
    void shouldRejectSummaryAndRawHistoryAboveUsableInputBudget() {
        ChatSummaryProperties properties = validProperties(768, 1_024);
        ChatContextProperties context = new ChatContextProperties(
                4_096,
                1_024,
                512
        );

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ChatSummaryConfiguration(
                        properties,
                        context
                ));
    }

    private static ChatSummaryProperties validProperties(
            long targetOutputTokens,
            long maxOutputTokens
    ) {
        return validProperties(
                targetOutputTokens,
                maxOutputTokens,
                new ChatSummaryProperties.Worker(
                        1, 2, 100, 10,
                        Duration.ofSeconds(5),
                        Duration.ofMinutes(1),
                        Duration.ofSeconds(60),
                        "agent-1"
                ),
                validRetry()
        );
    }

    private static ChatSummaryProperties validProperties(
            long targetOutputTokens,
            long maxOutputTokens,
            ChatSummaryProperties.Worker worker,
            ChatSummaryProperties.Retry retry
    ) {
        return new ChatSummaryProperties(
                false,
                true,
                false,
                1,
                4_096,
                30,
                4,
                3_072,
                200,
                1_048_576,
                12_000,
                targetOutputTokens,
                maxOutputTokens,
                1_024,
                "conversation-summary-v1",
                "qwen-plus",
                0.1,
                Duration.ofSeconds(15),
                worker,
                retry
        );
    }

    private static ChatSummaryProperties.Retry validRetry() {
        return new ChatSummaryProperties.Retry(
                5,
                Duration.ofSeconds(10),
                Duration.ofMinutes(10),
                Duration.ofSeconds(5)
        );
    }
}
