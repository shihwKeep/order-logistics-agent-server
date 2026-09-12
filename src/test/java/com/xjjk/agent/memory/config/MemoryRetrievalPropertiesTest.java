package com.xjjk.agent.memory.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryRetrievalPropertiesTest {

    @Test
    void enforcesBoundedCandidateAndSelectionSizes() {
        assertThatThrownBy(() -> new MemoryRetrievalProperties(101, 5, 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MemoryRetrievalProperties(10, 11, 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MemoryRetrievalProperties(10, 5, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
