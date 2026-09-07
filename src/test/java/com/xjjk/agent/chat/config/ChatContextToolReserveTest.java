package com.xjjk.agent.chat.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatContextToolReserveTest {

    @Test
    void keepsToolSchemaReserveSeparateFromEstimatorSafetyMargin() {
        ChatContextProperties properties = new ChatContextProperties(8192, 1024, 768);

        assertThat(properties.maxInputTokens()).isEqualTo(8192);
        assertThat(properties.safetyMarginTokens()).isEqualTo(1024);
        assertThat(properties.toolReserveTokens()).isEqualTo(768);
    }
}
