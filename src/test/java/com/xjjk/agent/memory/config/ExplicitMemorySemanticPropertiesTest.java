package com.xjjk.agent.memory.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExplicitMemorySemanticPropertiesTest {

    @Test
    void acceptsAProductionConfidenceThreshold() {
        ExplicitMemorySemanticProperties properties = new ExplicitMemorySemanticProperties(0.85);

        assertThat(properties.confidenceThreshold()).isEqualTo(0.85);
    }

    @Test
    void rejectsInvalidConfidenceThresholds() {
        for (double value : new double[]{0.0, -0.1, Double.NaN, Double.POSITIVE_INFINITY, 1.01}) {
            assertThatThrownBy(() -> new ExplicitMemorySemanticProperties(value))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
