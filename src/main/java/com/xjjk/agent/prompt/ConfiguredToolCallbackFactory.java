package com.xjjk.agent.prompt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** 根据 Nacos 配置重建模型可见的工具和参数描述。 */
@Component
public class ConfiguredToolCallbackFactory {

    private final ObjectMapper objectMapper;
    private final AgentPromptCatalogProperties promptCatalog;

    public ConfiguredToolCallbackFactory(
            ObjectMapper objectMapper,
            AgentPromptCatalogProperties promptCatalog) {
        this.objectMapper = objectMapper;
        this.promptCatalog = promptCatalog;
    }

    public ToolCallback[] from(Object toolObject) {
        return Arrays.stream(ToolCallbacks.from(toolObject))
                .map(this::configure)
                .toArray(ToolCallback[]::new);
    }

    private ToolCallback configure(ToolCallback delegate) {
        String name = delegate.getToolDefinition().name();
        String path = "agent.ai.prompt.catalog.tools[" + name + "]";
        AgentPromptCatalogProperties.ToolPrompt configured =
                promptCatalog.tools().get(name);
        if (configured == null) {
            throw invalid(path, "缺少工具配置");
        }
        try {
            JsonNode parsed = objectMapper.readTree(
                    delegate.getToolDefinition().inputSchema());
            if (!(parsed instanceof ObjectNode schema)
                    || !(schema.get("properties") instanceof ObjectNode properties)) {
                throw invalid(path, "工具输入 Schema 缺少 properties 对象");
            }
            Set<String> schemaNames = new LinkedHashSet<>();
            properties.fieldNames().forEachRemaining(schemaNames::add);
            Set<String> configuredNames = configured.parameters().keySet();
            if (!schemaNames.equals(configuredNames)) {
                Set<String> missing = new LinkedHashSet<>(schemaNames);
                missing.removeAll(configuredNames);
                Set<String> extra = new LinkedHashSet<>(configuredNames);
                extra.removeAll(schemaNames);
                throw invalid(path,
                        "参数描述不匹配，缺少=" + missing + "，多余=" + extra);
            }
            for (Map.Entry<String, String> entry
                    : configured.parameters().entrySet()) {
                if (!StringUtils.hasText(entry.getValue())
                        || !(properties.get(entry.getKey()) instanceof ObjectNode parameter)) {
                    throw invalid(path + ".parameters[" + entry.getKey() + "]",
                            "参数描述无效");
                }
                parameter.put("description", entry.getValue());
            }
            DefaultToolDefinition definition = new DefaultToolDefinition(
                    name, configured.description(),
                    objectMapper.writeValueAsString(schema));
            return new DelegatingConfiguredToolCallback(delegate, definition);
        } catch (JsonProcessingException exception) {
            throw invalid(path, "工具输入 Schema 不是合法 JSON");
        }
    }

    private static IllegalStateException invalid(String path, String reason) {
        return new IllegalStateException("提示词配置无效: " + path + "，" + reason);
    }
}
