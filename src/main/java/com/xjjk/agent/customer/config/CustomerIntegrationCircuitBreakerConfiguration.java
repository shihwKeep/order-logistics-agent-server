package com.xjjk.agent.customer.config;

import com.xjjk.agent.customer.service.CustomerServiceUnavailableException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class CustomerIntegrationCircuitBreakerConfiguration {
    public static final String CUSTOMER_SEARCH = "customerSearch";

    @Bean
    Customizer<Resilience4JCircuitBreakerFactory> customerCircuitBreaker(
            CustomerIntegrationProperties properties,
            TimeLimiterRegistry timeLimiterRegistry) {
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
        registerTimeLimiterConfiguration(properties, timeLimiterRegistry);
        return factory -> factory.configure(builder -> builder
                .circuitBreakerConfig(breaker).timeLimiterConfig(timeLimiter), CUSTOMER_SEARCH);
    }

    /**
     * Spring Cloud CircuitBreaker 会优先读取 TimeLimiterRegistry 的命名配置。
     * 仅配置 factory builder 时，customerSearch 可能回退到 Resilience4j 默认的一秒超时。
     */
    static void registerTimeLimiterConfiguration(
            CustomerIntegrationProperties properties,
            TimeLimiterRegistry timeLimiterRegistry) {
        TimeLimiterConfig timeLimiter = TimeLimiterConfig.custom()
                .timeoutDuration(properties.getResilience().getCallTimeout())
                .cancelRunningFuture(true)
                .build();
        timeLimiterRegistry.getConfiguration(CUSTOMER_SEARCH)
                .ifPresent(ignored -> timeLimiterRegistry.removeConfiguration(CUSTOMER_SEARCH));
        timeLimiterRegistry.addConfiguration(CUSTOMER_SEARCH, timeLimiter);
    }
}
