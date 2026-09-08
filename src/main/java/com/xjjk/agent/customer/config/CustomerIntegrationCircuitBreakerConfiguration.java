package com.xjjk.agent.customer.config;

import com.xjjk.agent.customer.service.CustomerServiceUnavailableException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class CustomerIntegrationCircuitBreakerConfiguration {
    public static final String CUSTOMER_SEARCH = "customerSearch";

    @Bean
    Customizer<Resilience4JCircuitBreakerFactory> customerCircuitBreaker(
            CustomerIntegrationProperties properties) {
        CustomerIntegrationProperties.Resilience value = properties.getResilience();
        if (value.getMinimumNumberOfCalls() > value.getSlidingWindowSize()) {
            throw new IllegalArgumentException("客户熔断器最小调用数不能大于滑动窗口");
        }
        CircuitBreakerConfig breaker = CircuitBreakerConfig.custom()
                .slidingWindowSize(value.getSlidingWindowSize())
                .minimumNumberOfCalls(value.getMinimumNumberOfCalls())
                .permittedNumberOfCallsInHalfOpenState(value.getPermittedCallsInHalfOpenState())
                .failureRateThreshold(value.getFailureRateThreshold())
                .waitDurationInOpenState(value.getOpenStateWaitDuration())
                .recordException(CustomerServiceUnavailableException.class::isInstance)
                .build();
        TimeLimiterConfig timeLimiter = TimeLimiterConfig.custom()
                .timeoutDuration(value.getCallTimeout()).cancelRunningFuture(true).build();
        return factory -> factory.configure(builder -> builder
                .circuitBreakerConfig(breaker).timeLimiterConfig(timeLimiter), CUSTOMER_SEARCH);
    }
}
