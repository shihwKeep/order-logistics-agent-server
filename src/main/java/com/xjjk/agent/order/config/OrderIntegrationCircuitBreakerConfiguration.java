package com.xjjk.agent.order.config;

import com.xjjk.agent.order.service.OrderServiceUnavailableException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 为订单搜索与订单物流建立相互隔离的熔断状态机。 */
@Configuration(proxyBeanMethods = false)
public class OrderIntegrationCircuitBreakerConfiguration {

    public static final String ORDER_SEARCH = "agentOrderSearch";
    public static final String ORDER_LOGISTICS = "agentOrderLogistics";

    @Bean
    public Customizer<Resilience4JCircuitBreakerFactory> orderCircuitBreakers(
            OrderIntegrationProperties properties,
            TimeLimiterRegistry timeLimiterRegistry
    ) {
        CircuitBreakerConfig circuitBreakerConfig = buildConfig(properties);
        TimeLimiterConfig timeLimiterConfig = TimeLimiterConfig.custom()
                .timeoutDuration(properties.getResilience().getCallTimeout())
                .cancelRunningFuture(true)
                .build();
        registerTimeLimiterConfigurations(properties, timeLimiterRegistry);
        return factory -> factory.configure(
                builder -> builder
                        .circuitBreakerConfig(circuitBreakerConfig)
                        .timeLimiterConfig(timeLimiterConfig),
                ORDER_SEARCH, ORDER_LOGISTICS);
    }

    /**
     * Spring Cloud CircuitBreaker 优先使用 TimeLimiterRegistry 中的命名配置，
     * 不能只把 TimeLimiterConfig 放进 factory builder，否则会回退到 Resilience4j
     * 默认的 1 秒超时。
     */
    static void registerTimeLimiterConfigurations(
            OrderIntegrationProperties properties,
            TimeLimiterRegistry timeLimiterRegistry
    ) {
        TimeLimiterConfig timeLimiterConfig = TimeLimiterConfig.custom()
                .timeoutDuration(properties.getResilience().getCallTimeout())
                .cancelRunningFuture(true)
                .build();
        for (String circuitBreakerName : new String[]{ORDER_SEARCH, ORDER_LOGISTICS}) {
            timeLimiterRegistry.getConfiguration(circuitBreakerName)
                    .ifPresent(ignored -> timeLimiterRegistry.removeConfiguration(circuitBreakerName));
            timeLimiterRegistry.addConfiguration(circuitBreakerName, timeLimiterConfig);
        }
    }

    /** 暴露纯配置构建方法，便于验证开路、半开与异常分类。 */
    public static CircuitBreakerConfig buildConfig(
            OrderIntegrationProperties properties
    ) {
        OrderIntegrationProperties.Resilience resilience =
                properties.getResilience();
        if (resilience.getMinimumNumberOfCalls()
                > resilience.getSlidingWindowSize()) {
            throw new IllegalArgumentException(
                    "minimum-number-of-calls 不能大于 sliding-window-size");
        }
        if (resilience.getOpenStateWaitDuration().isZero()
                || resilience.getOpenStateWaitDuration().isNegative()
                || resilience.getCallTimeout().isZero()
                || resilience.getCallTimeout().isNegative()) {
            throw new IllegalArgumentException("订单韧性时间参数必须大于 0");
        }
        return CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(resilience.getSlidingWindowSize())
                .minimumNumberOfCalls(resilience.getMinimumNumberOfCalls())
                .permittedNumberOfCallsInHalfOpenState(
                        resilience.getPermittedCallsInHalfOpenState())
                .failureRateThreshold(resilience.getFailureRateThreshold())
                .waitDurationInOpenState(
                        resilience.getOpenStateWaitDuration())
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                // 只有安全映射后的下游不可用异常计入失败率；调用参数错误不污染熔断器。
                .recordException(OrderServiceUnavailableException.class::isInstance)
                .build();
    }
}
