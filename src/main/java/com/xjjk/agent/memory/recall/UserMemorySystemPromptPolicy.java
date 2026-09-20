package com.xjjk.agent.memory.recall;

import com.xjjk.agent.prompt.AgentPromptCatalogProperties;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** 固定的记忆权限规则；估算和实际模型请求必须共同使用。 */
@Component
public class UserMemorySystemPromptPolicy {

    private final String policy;

    public UserMemorySystemPromptPolicy(AgentPromptCatalogProperties promptCatalog) {
        this.policy = Objects.requireNonNull(promptCatalog, "promptCatalog")
                .context().systemPolicy();
    }

    public String enhance(String baseSystemPrompt) {
        if (baseSystemPrompt == null || baseSystemPrompt.isBlank()) {
            throw new IllegalArgumentException("系统提示词不能为空");
        }
        return baseSystemPrompt.contains(policy) ? baseSystemPrompt : baseSystemPrompt + policy;
    }
}
