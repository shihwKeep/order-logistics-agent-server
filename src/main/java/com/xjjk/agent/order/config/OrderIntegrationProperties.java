package com.xjjk.agent.order.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** Agent 调用订单内部接口时的生产韧性参数，实际值由 Nacos 注入。 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "integration.order")
public class OrderIntegrationProperties {

    @Valid
    @NotNull
    private Resilience resilience = new Resilience();

    /** 两个订单调用熔断器共用的边界参数，但使用相互隔离的实例状态。 */
    @Getter
    @Setter
    public static class Resilience {

        @Min(2)
        @Max(1000)
        private int slidingWindowSize = 20;

        @Min(2)
        @Max(1000)
        private int minimumNumberOfCalls = 10;

        @Min(1)
        @Max(100)
        private int permittedCallsInHalfOpenState = 3;

        @DecimalMin("1.0")
        @DecimalMax("100.0")
        private float failureRateThreshold = 50F;

        @NotNull
        private Duration openStateWaitDuration = Duration.ofSeconds(30);

        @NotNull
        private Duration callTimeout = Duration.ofSeconds(8);
    }
}
