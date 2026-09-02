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

        public OAuth {
            if (isMissingOrUnresolved(clientId) || isMissingOrUnresolved(clientSecret)) {
                throw new IllegalArgumentException(
                        "SSPX OAuth credentials are missing; configure the required environment variables"
                );
            }
        }

        private static boolean isMissingOrUnresolved(String value) {
            return value == null
                    || value.isBlank()
                    || (value.startsWith("${") && value.endsWith("}"));
        }
    }
}
