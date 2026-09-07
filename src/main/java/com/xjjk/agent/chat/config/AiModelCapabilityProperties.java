package com.xjjk.agent.chat.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 当前接入模型的能力限制。
 *
 * 当前配置按纯文本、非思考模式使用。
 * 数值由官方文档核对后维护，不是框架自动发现的模型能力。
 * 更换模型或模式时，需要同步核对这些限制。
 *
 * @param model 这组能力限制对应的模型名称
 * @param contextTokens 模型上下文总上限
 * @param maxInputTokens 模型单次输入上限
 * @param maxOutputTokens 模型支持的单次输出上限，
 *                        不等于当前请求实际设置的输出上限
 */
@Validated
@ConfigurationProperties(prefix = "agent.ai.model-capability")
public record AiModelCapabilityProperties(

        @NotBlank(message = "模型能力配置中的模型名称不能为空")
        String model,

        @Min(value = 1, message = "模型上下文上限必须大于 0")
        long contextTokens,

        @Min(value = 1, message = "模型输入上限必须大于 0")
        long maxInputTokens,

        @Min(value = 1, message = "模型输出上限必须大于 0")
        long maxOutputTokens

) {

    public AiModelCapabilityProperties {
        if (maxInputTokens > contextTokens) {
            throw new IllegalArgumentException(
                    "模型输入上限不能超过上下文总上限"
            );
        }

        if (maxOutputTokens > contextTokens) {
            throw new IllegalArgumentException(
                    "模型输出上限不能超过上下文总上限"
            );
        }
    }
}
