package com.xjjk.agent.order.client;

import com.xjjk.agent.order.config.OrderIntegrationCircuitBreakerConfiguration;
import com.xjjk.agent.order.config.OrderIntegrationProperties;
import com.xjjk.agent.order.service.OrderServiceUnavailableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderServiceResilienceTest {

    @Test
    void opensAfterConfiguredLogicalFailuresAndRecoversThroughHalfOpenProbe() {
        OrderIntegrationProperties properties = new OrderIntegrationProperties();
        properties.getResilience().setSlidingWindowSize(4);
        properties.getResilience().setMinimumNumberOfCalls(4);
        properties.getResilience().setPermittedCallsInHalfOpenState(1);
        properties.getResilience().setFailureRateThreshold(50F);
        properties.getResilience().setOpenStateWaitDuration(Duration.ofSeconds(30));

        CircuitBreaker breaker = CircuitBreaker.of(
                "agentOrderSearch",
                OrderIntegrationCircuitBreakerConfiguration.buildConfig(properties));

        for (int index = 0; index < 4; index++) {
            assertThatThrownBy(() -> breaker.executeSupplier(() -> {
                throw new OrderServiceUnavailableException("订单服务调用失败");
            })).isInstanceOf(OrderServiceUnavailableException.class);
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThatThrownBy(() -> breaker.executeSupplier(() -> "blocked"))
                .isInstanceOf(CallNotPermittedException.class);

        breaker.transitionToHalfOpenState();
        assertThat(breaker.executeSupplier(() -> "ok")).isEqualTo("ok");
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void ignoresCallerValidationErrorsWhenCalculatingFailureRate() {
        OrderIntegrationProperties properties = new OrderIntegrationProperties();
        properties.getResilience().setSlidingWindowSize(2);
        properties.getResilience().setMinimumNumberOfCalls(2);

        CircuitBreaker breaker = CircuitBreaker.of(
                "agentOrderSearch",
                OrderIntegrationCircuitBreakerConfiguration.buildConfig(properties));

        for (int index = 0; index < 2; index++) {
            assertThatThrownBy(() -> breaker.executeSupplier(() -> {
                throw new IllegalArgumentException("请求参数错误");
            })).isInstanceOf(IllegalArgumentException.class);
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
    }
}
