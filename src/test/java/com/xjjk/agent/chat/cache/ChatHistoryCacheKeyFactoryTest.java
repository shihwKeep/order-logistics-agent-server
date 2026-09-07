package com.xjjk.agent.chat.cache;

import com.xjjk.agent.chat.config.ChatHistoryCacheProperties;
import com.xjjk.agent.chat.config.ChatHistoryProperties;
import com.xjjk.agent.chat.domain.memory.ChatHistoryCursor;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatHistoryCacheKeyFactoryTest {

    @Test
    void keyContainsIdentityCursorAndPolicy() {
        ChatHistoryCacheProperties properties = properties();
        ChatHistoryCacheKeyFactory factory = new ChatHistoryCacheKeyFactory(
                properties,
                new ChatHistoryProperties(200, 1_048_576)
        );
        ChatHistoryCursor cursor = new ChatHistoryCursor(
                1,
                10567,
                "conversation-1",
                8,
                16,
                17
        );

        assertThat(factory.create(cursor)).isEqualTo(
                "agent:chat:history:v1:1:10567:conversation-1:8:16:"
                        + "m200-b1048576"
        );
    }

    @Test
    void keySeparatesTenantAndUser() {
        ChatHistoryCacheKeyFactory factory = new ChatHistoryCacheKeyFactory(
                properties(),
                new ChatHistoryProperties(200, 1_048_576)
        );

        String first = factory.create(new ChatHistoryCursor(
                1, 10567, "conversation-1", 8, 16, 17));
        String second = factory.create(new ChatHistoryCursor(
                2, 10567, "conversation-1", 8, 16, 17));
        String third = factory.create(new ChatHistoryCursor(
                1, 10568, "conversation-1", 8, 16, 17));

        assertThat(first).isNotEqualTo(second).isNotEqualTo(third);
    }

    @Test
    void cursorRejectsDiscontinuousBoundary() {
        assertThatThrownBy(() -> new ChatHistoryCursor(
                1, 10567, "conversation-1", 8, 16, 18))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不连续");
    }

    @Test
    void propertiesRejectInvalidTtlAndPoolRange() {
        assertThatThrownBy(() -> new ChatHistoryCacheProperties(
                true,
                "agent:chat:history:v1",
                Duration.ZERO,
                Duration.ZERO,
                2,
                1,
                100
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private ChatHistoryCacheProperties properties() {
        return new ChatHistoryCacheProperties(
                true,
                "agent:chat:history:v1",
                Duration.ofMinutes(30),
                Duration.ofMinutes(5),
                1,
                2,
                100
        );
    }
}
