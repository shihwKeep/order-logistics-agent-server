package com.xjjk.agent.aftersale.config;

import com.xjjk.agent.aftersale.service.AfterSaleServiceUnavailableException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 售后搜索和详情分别使用独立熔断器，避免一种慢调用拖垮另一种能力。 */
@Configuration(proxyBeanMethods = false)
public class AfterSaleCircuitBreakerConfiguration {
    public static final String SEARCH = "agentAfterSaleSearch";
    public static final String DETAIL = "agentAfterSaleDetail";

    @Bean
    Customizer<Resilience4JCircuitBreakerFactory> afterSaleCircuitBreakers(
            AfterSaleIntegrationProperties properties) {
        AfterSaleIntegrationProperties.Resilience value = properties.getResilience();
        if (value.getMinimumNumberOfCalls() > value.getSlidingWindowSize()) {
            throw new IllegalArgumentException("售后熔断器最小调用数不能大于滑动窗口");
        }
        if (value.getOpenStateWaitDuration().isZero()
                || value.getOpenStateWaitDuration().isNegative()
                || value.getCallTimeout().isZero()
                || value.getCallTimeout().isNegative()) {
            throw new IllegalArgumentException("售后韧性时间参数必须大于 0");
        }
        CircuitBreakerConfig breaker = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(value.getSlidingWindowSize())
                .minimumNumberOfCalls(value.getMinimumNumberOfCalls())
                .permittedNumberOfCallsInHalfOpenState(value.getPermittedCallsInHalfOpenState())
                .failureRateThreshold(value.getFailureRateThreshold())
                .waitDurationInOpenState(value.getOpenStateWaitDuration())
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .recordException(AfterSaleServiceUnavailableException.class::isInstance)
                .build();
        TimeLimiterConfig limiter = TimeLimiterConfig.custom()
                .timeoutDuration(value.getCallTimeout())
                .cancelRunningFuture(true)
                .build();
        return factory -> factory.configure(builder -> builder
                .circuitBreakerConfig(breaker)
                .timeLimiterConfig(limiter), SEARCH, DETAIL);
    }
}
