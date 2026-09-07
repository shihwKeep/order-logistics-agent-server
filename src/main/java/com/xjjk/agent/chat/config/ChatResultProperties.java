package com.xjjk.agent.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 结构化工具结果持久化配置，生产值统一由 Nacos 提供。 */
@Validated
@ConfigurationProperties(prefix = "agent.chat.result")
public record ChatResultProperties(
        boolean enabled,
        int maxPayloadBytes) {

    private static final int MYSQL_MEDIUMTEXT_MAX_BYTES = 16_777_215;

    public ChatResultProperties {
        if (maxPayloadBytes <= 0 || maxPayloadBytes > MYSQL_MEDIUMTEXT_MAX_BYTES) {
            throw new IllegalArgumentException(
                    "结构化结果最大字节数必须在 1 到 16777215 之间");
        }
    }
}
