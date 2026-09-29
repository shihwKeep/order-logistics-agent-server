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
        String system,

        @NotBlank(message = "知识库二阶段回答边界不能为空")
        String knowledgeAnswerBoundary

) {

    /** 兼容不关心二阶段提示词的单元测试构造方式。 */
    public AiPromptProperties(String version, String system) {
        this(version, system,
                "不得输出内部工具名称，不得描述工具调用步骤，不得要求用户提供订单号或执行查询步骤。");
    }
}
