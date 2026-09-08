package com.xjjk.agent.aftersale.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** Agent 调用售后内部接口的连接与韧性配置，实际值由 Nacos 注入。 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "integration.aftersale")
public class AfterSaleIntegrationProperties {
    @NotBlank
    private String baseUrl = "http://127.0.0.1:9";

    @NotBlank
    private String internalToken = "disabled-after-sale-integration";

    @Valid
    @NotNull
    private Resilience resilience = new Resilience();

    @Getter
    @Setter
    public static class Resilience {
        @Min(2) @Max(1000) private int slidingWindowSize = 20;
        @Min(1) @Max(1000) private int minimumNumberOfCalls = 10;
        @DecimalMin("1.0") @DecimalMax("100.0") private float failureRateThreshold = 50F;
        @Min(1) @Max(100) private int permittedCallsInHalfOpenState = 3;
        @NotNull private Duration openStateWaitDuration = Duration.ofSeconds(30);
        @NotNull private Duration callTimeout = Duration.ofSeconds(4);
    }
}
