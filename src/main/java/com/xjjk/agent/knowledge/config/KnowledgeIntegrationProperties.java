package com.xjjk.agent.knowledge.config;

import jakarta.annotation.PostConstruct;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** Agent 调用 Knowledge Service 内部检索接口的服务端配置。 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "integration.knowledge")
public class KnowledgeIntegrationProperties {
    @NotBlank
    private String baseUrl = "http://127.0.0.1:9";
    private String internalSecret = "disabled-knowledge-integration-secret";
    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration readTimeout = Duration.ofSeconds(10);

    @PostConstruct
    void validate() {
        if (internalSecret == null || internalSecret.length() < 32) {
            throw new IllegalArgumentException("integration.knowledge.internal-secret 至少需要32个字符");
        }
        if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()
                || readTimeout == null || readTimeout.isZero() || readTimeout.isNegative()) {
            throw new IllegalArgumentException("知识检索连接和读取超时必须大于0");
        }
    }
}
