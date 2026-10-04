package com.xjjk.agent.customer.config;

import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerIntegrationCircuitBreakerConfigurationTest {

    @Test
    void registersConfiguredTimeLimiterForCustomerSearch() {
        CustomerIntegrationProperties properties = new CustomerIntegrationProperties();
        properties.getResilience().setCallTimeout(Duration.ofSeconds(7));
        TimeLimiterRegistry registry = TimeLimiterRegistry.ofDefaults();

        CustomerIntegrationCircuitBreakerConfiguration.registerTimeLimiterConfiguration(
                properties, registry);

        assertThat(registry.getConfiguration(
                CustomerIntegrationCircuitBreakerConfiguration.CUSTOMER_SEARCH))
                .get()
                .satisfies(config -> {
                    assertThat(config.getTimeoutDuration()).isEqualTo(Duration.ofSeconds(7));
                    assertThat(config.shouldCancelRunningFuture()).isTrue();
                });
    }
}
