package com.xjjk.agent.prompt;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** 在应用接收流量前校验提示词目录的完整性和模板协议。 */
@Component
public class AgentPromptCatalogValidator implements SmartInitializingSingleton {

    private static final String PREFIX = "agent.ai.prompt.catalog.";
    private static final Set<String> REQUIRED_TOOLS = Set.of(
            "search_products", "search_orders", "get_order_logistics",
            "search_customers", "list_customer_orders",
            "search_after_sales", "get_after_sale_detail",
            "search_knowledge");

    private final AgentPromptCatalogProperties properties;
    private final StrictPromptTemplateRenderer renderer;

    public AgentPromptCatalogValidator(
            AgentPromptCatalogProperties properties,
            StrictPromptTemplateRenderer renderer) {
        this.properties = properties;
        this.renderer = renderer;
    }

    @Override
    public void afterSingletonsInstantiated() {
        validateTemplate("summary.user-template",
                properties.summary().userTemplate(),
                Set.of("promptVersion", "targetOutputTokens",
                        "formatCorrection", "inputJson"));
        validateTemplate("memory.explicit.user-template",
                properties.memory().explicit().userTemplate(),
                Set.of("promptVersion", "sourceMessage"));
        validateTemplate("memory.implicit.user-template",
                properties.memory().implicit().userTemplate(),
                Set.of("promptVersion", "previousMessage", "currentMessage"));
        validateTemplate("memory.evidence.user-template",
                properties.memory().evidence().userTemplate(), Set.of("inputJson"));
        validateTemplate("context.summary.header-template",
                properties.context().summary().headerTemplate(),
                Set.of("topic", "currentState"));
        validateTemplate("context.user-memory.entry-template",
                properties.context().userMemory().entryTemplate(),
                Set.of("sourceType", "category", "content"));
        validateTools(properties.tools());
    }

    private void validateTemplate(
            String relativePath,
            String template,
            Set<String> expected) {
        Set<String> actual = renderer.placeholders(template);
        if (!actual.equals(expected)) {
            Set<String> missing = new LinkedHashSet<>(expected);
            missing.removeAll(actual);
            Set<String> unexpected = new LinkedHashSet<>(actual);
            unexpected.removeAll(expected);
            throw invalid(relativePath,
                    "占位符不匹配，缺少=" + missing + "，多余=" + unexpected);
        }
    }

    private static void validateTools(
            Map<String, AgentPromptCatalogProperties.ToolPrompt> tools) {
        Set<String> actual = tools.keySet();
        if (!actual.equals(REQUIRED_TOOLS)) {
            Set<String> missing = new LinkedHashSet<>(REQUIRED_TOOLS);
            missing.removeAll(actual);
            Set<String> unexpected = new LinkedHashSet<>(actual);
            unexpected.removeAll(REQUIRED_TOOLS);
            throw invalid("tools",
                    "工具清单不匹配，缺少=" + missing + "，多余=" + unexpected);
        }
    }

    private static IllegalStateException invalid(String path, String reason) {
        return new IllegalStateException(
                "提示词配置无效: " + PREFIX + path + "，" + reason);
    }
}
