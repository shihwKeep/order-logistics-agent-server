package com.xjjk.agent.chat.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "agent.ai.prompt")
public record AiPromptProperties(

        @NotBlank(message = "系统提示词版本不能为空")
        String version,

        @NotBlank(message = "系统提示词正文不能为空")
        String system

) {
}