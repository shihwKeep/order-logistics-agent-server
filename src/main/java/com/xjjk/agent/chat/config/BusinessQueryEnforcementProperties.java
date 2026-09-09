package com.xjjk.agent.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 实时业务查询结果约束；生产值只从 Nacos 注入。 */
@ConfigurationProperties(prefix = "agent.chat.business-query-enforcement")
public record BusinessQueryEnforcementProperties(boolean enabled) {
}
