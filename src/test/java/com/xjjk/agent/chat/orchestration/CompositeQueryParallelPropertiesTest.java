package com.xjjk.agent.chat.orchestration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompositeQueryParallelPropertiesTest {

    @Test
    void rejectsAnUnboundedOrInvalidPoolConfiguration() {
        assertThatThrownBy(() -> new CompositeQueryParallelProperties(0, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CompositeQueryParallelProperties(4, 2, 32))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CompositeQueryParallelProperties(4, 8, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
