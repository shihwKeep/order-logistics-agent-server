package com.xjjk.agent.auth.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SspxOAuthPropertiesTest {

    @Test
    void rejectsUnresolvedEnvironmentPlaceholders() {
        assertThatThrownBy(() -> new SspxOAuthProperties.OAuth(
                "${SSPX_OAUTH_CLIENT_ID}",
                "${SSPX_OAUTH_CLIENT_SECRET}"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SSPX OAuth")
                .hasMessageContaining("environment variables");
    }
}
