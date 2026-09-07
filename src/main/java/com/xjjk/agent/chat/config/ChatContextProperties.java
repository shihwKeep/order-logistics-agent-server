package com.xjjk.agent.chat.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 模型上下文预算配置。
 *
 * 应用主动限制输入规模，不代表模型官方支持的最大输入长度。
 * 最终可用输入预算还需要结合模型限制、输出预留共同计算。
 *
 * @param maxInputTokens 应用允许的输入 Token 上限，包含系统提示词和当前问题
 * @param safetyMarginTokens 安全余量，用于降低估算偏差造成超限的风险
 * @param toolReserveTokens 工具定义及工具调用协议的输入预留
 */
@Validated
@ConfigurationProperties(prefix = "agent.chat.context")
public record ChatContextProperties(

        @Min(value = 1, message = "应用输入 Token 上限必须大于 0")
        int maxInputTokens,

        @Min(value = 1, message = "Token 安全余量必须大于 0")
        int safetyMarginTokens,

        @Min(value = 1, message = "工具 Token 预留必须大于 0")
        int toolReserveTokens

) {

    public ChatContextProperties {
        if ((long) safetyMarginTokens + toolReserveTokens >= maxInputTokens) {
            throw new IllegalArgumentException(
                    "Token 安全余量与工具预留之和必须小于应用输入上限"
            );
        }
    }
}
