package com.xjjk.agent.memory.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserMemoryCursorPropertiesTest {

    @Test
    void rejectsShortOrBlankSecrets() {
        assertThatThrownBy(() -> new UserMemoryCursorProperties("short"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UserMemoryCursorProperties(" ".repeat(32)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
