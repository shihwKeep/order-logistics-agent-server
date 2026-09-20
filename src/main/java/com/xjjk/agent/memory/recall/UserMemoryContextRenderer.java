package com.xjjk.agent.memory.recall;

import com.xjjk.agent.memory.config.MemoryRetrievalProperties;
import com.xjjk.agent.memory.config.UserMemoryProperties;
import com.xjjk.agent.chat.service.memory.QwenTextTokenEstimator;
import com.xjjk.agent.prompt.AgentPromptCatalogProperties;
import com.xjjk.agent.prompt.StrictPromptTemplateRenderer;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** 把已终审记忆渲染为单个、低权限、不可信 USER 数据块。 */
@Component
public class UserMemoryContextRenderer {
    private final int maxContentLength;
    private final int maxEntries;
    private final int maxTokens;
    private final QwenTextTokenEstimator tokenEstimator;
    private final AgentPromptCatalogProperties.UserMemoryContext prompts;
    private final StrictPromptTemplateRenderer promptRenderer;

    public UserMemoryContextRenderer(
            UserMemoryProperties memoryProperties,
            MemoryRetrievalProperties retrievalProperties,
            QwenTextTokenEstimator tokenEstimator,
            AgentPromptCatalogProperties promptCatalog,
            StrictPromptTemplateRenderer promptRenderer) {
        this.maxContentLength = memoryProperties.maxContentLength();
        this.maxEntries = retrievalProperties.maxSelected();
        this.maxTokens = memoryProperties.contextMaxTokens();
        this.tokenEstimator = tokenEstimator;
        this.prompts = promptCatalog.context().userMemory();
        this.promptRenderer = promptRenderer;
    }

    public String render(List<RecalledMemory> memories) {
        if (memories == null || memories.isEmpty()) {
            return null;
        }
        List<RecalledMemory> bounded = List.copyOf(memories);
        if (bounded.size() > maxEntries) {
            throw new IllegalArgumentException("记忆上下文条目超过上限");
        }
        String prefix = new StringBuilder(prompts.openMarker()).append('\n')
                .append(prompts.header()).append('\n')
                .toString();
        StringBuilder result = new StringBuilder(prefix);
        for (RecalledMemory memory : bounded) {
            String line = promptRenderer.render(
                    "agent.ai.prompt.catalog.context.user-memory.entry-template",
                    prompts.entryTemplate(),
                    Map.of("sourceType", safeMetadata(memory.sourceType()),
                            "category", safeMetadata(memory.category()),
                            "content", safeContent(memory.content()))) + '\n';
            String trial = result.toString() + line + prompts.closeMarker();
            if (tokenEstimator.estimate(trial) > maxTokens) {
                break;
            }
            result.append(line);
        }
        if (result.length() == prefix.length()) {
            return null;
        }
        return result.append(prompts.closeMarker()).toString();
    }

    private String safeMetadata(String value) {
        if (value == null || !value.matches("[A-Z0-9_]{1,64}")) {
            throw new IllegalArgumentException("记忆上下文元数据不合法");
        }
        return value;
    }

    private String safeContent(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("记忆上下文正文不能为空");
        }
        StringBuilder safe = new StringBuilder();
        value.codePoints().limit(maxContentLength).forEach(codePoint -> {
            if (codePoint == '[') safe.append('［');
            else if (codePoint == ']') safe.append('］');
            else if (codePoint == '\r' || codePoint == '\n' || codePoint == '\t') safe.append(' ');
            else if (!Character.isISOControl(codePoint)) safe.appendCodePoint(codePoint);
        });
        if (safe.toString().isBlank()) {
            throw new IllegalArgumentException("记忆上下文正文清洗后为空");
        }
        return safe.toString();
    }
}
