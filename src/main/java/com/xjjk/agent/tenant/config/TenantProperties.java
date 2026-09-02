package com.xjjk.agent.tenant.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.tenant")
public record TenantProperties(
        @Positive long fixedId
) {
}
