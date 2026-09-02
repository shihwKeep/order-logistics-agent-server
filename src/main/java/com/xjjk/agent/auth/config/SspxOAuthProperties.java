package com.xjjk.agent.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "integration.sspx")
public record SspxOAuthProperties(
        String baseUrl,
        OAuth oauth
) {

    public record OAuth(
            String clientId,
            String clientSecret
    ) {
    }
}
