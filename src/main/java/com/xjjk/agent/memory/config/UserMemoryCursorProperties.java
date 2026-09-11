package com.xjjk.agent.memory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 签名分页游标配置；生产环境必须由安全配置中心提供独立密钥。 */
@Validated
@ConfigurationProperties(prefix = "agent.memory.cursor")
public record UserMemoryCursorProperties(String secret) {

    public UserMemoryCursorProperties {
        if (secret == null || secret.isBlank() || secret.length() < 32) {
            throw new IllegalArgumentException("用户记忆分页游标签名密钥至少需要32个字符");
        }
    }
}
