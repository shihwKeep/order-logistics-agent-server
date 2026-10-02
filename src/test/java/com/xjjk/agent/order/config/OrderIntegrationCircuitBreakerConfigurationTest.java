package com.xjjk.agent.order.config;

import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class OrderIntegrationCircuitBreakerConfigurationTest {

    @Test
    void registersConfiguredTimeLimiterForOrderCircuitBreakers() {
        OrderIntegrationProperties properties = new OrderIntegrationProperties();
        properties.getResilience().setCallTimeout(Duration.ofSeconds(30));
        TimeLimiterRegistry registry = TimeLimiterRegistry.ofDefaults();

        OrderIntegrationCircuitBreakerConfiguration.registerTimeLimiterConfigurations(
                properties, registry);

        assertThat(registry.getConfiguration(
                OrderIntegrationCircuitBreakerConfiguration.ORDER_SEARCH))
                .get()
                .extracting(config -> config.getTimeoutDuration())
                .isEqualTo(Duration.ofSeconds(30));
        assertThat(registry.getConfiguration(
                OrderIntegrationCircuitBreakerConfiguration.ORDER_LOGISTICS))
                .get()
                .extracting(config -> config.getTimeoutDuration())
                .isEqualTo(Duration.ofSeconds(30));
    }
}
