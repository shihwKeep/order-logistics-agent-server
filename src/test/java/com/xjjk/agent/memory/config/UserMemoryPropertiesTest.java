package com.xjjk.agent.memory.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class UserMemoryPropertiesTest {

    @Test
    void acceptsTheProductionShape() {
        validProperties(256);
    }

    @Test
    void rejectsZeroContextBudget() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> validProperties(0));
    }

    @Test
    void rejectsOversizedTextAndPageLimits() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new UserMemoryProperties(
                        true, true, 256, 513, 512, 50, 365,
                        "memory-v1", "qwen-plus", 0.1,
                        Duration.ofSeconds(10), 1, 10));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new UserMemoryProperties(
                        true, true, 256, 512, 512, 101, 365,
                        "memory-v1", "qwen-plus", 0.1,
                        Duration.ofSeconds(10), 1, 10));
    }

    @Test
    void rejectsInvalidModelRuntimeValues() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new UserMemoryProperties(
                        true, true, 256, 512, 512, 50, 365,
                        "", "qwen-plus", 0.1,
                        Duration.ofSeconds(10), 1, 10));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new UserMemoryProperties(
                        true, true, 256, 512, 512, 50, 365,
                        "memory-v1", "qwen-plus", 2.1,
                        Duration.ofSeconds(10), 1, 10));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new UserMemoryProperties(
                        true, true, 256, 512, 512, 50, 365,
                        "memory-v1", "qwen-plus", 0.1,
                        Duration.ZERO, 1, 10));
    }

    private UserMemoryProperties validProperties(int contextMaxTokens) {
        return new UserMemoryProperties(
                true,
                true,
                contextMaxTokens,
                512,
                512,
                50,
                365,
                "memory-explicit-v1",
                "qwen-plus",
                0.1,
                Duration.ofSeconds(10),
                1,
                10
        );
    }
}
