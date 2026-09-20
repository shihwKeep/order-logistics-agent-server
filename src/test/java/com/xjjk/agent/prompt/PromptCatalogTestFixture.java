package com.xjjk.agent.prompt;

import java.util.LinkedHashMap;
import java.util.Map;

public final class PromptCatalogTestFixture {

    private PromptCatalogTestFixture() {
    }

    public static AgentPromptCatalogProperties catalog() {
        Map<String, AgentPromptCatalogProperties.ToolPrompt> tools =
                new LinkedHashMap<>();
        put(tools, "search_products", "keyword", "pageIndex");
        put(tools, "search_orders", "identifier", "identifierType");
        put(tools, "get_order_logistics", "identifier", "identifierType");
        put(tools, "search_customers", "keyword", "matchType");
        put(tools, "list_customer_orders", "customerCode");
        put(tools, "search_after_sales", "identifierType", "identifier",
                "startTime", "endTime");
        put(tools, "get_after_sale_detail", "afterSaleCode");
        put(tools, "search_knowledge", "question");
        return new AgentPromptCatalogProperties(
                new AgentPromptCatalogProperties.Summary(
                        "CONFIGURED_SUMMARY_SYSTEM",
                        "version={promptVersion};tokens={targetOutputTokens};"
                                + "correction={formatCorrection};input={inputJson}"),
                new AgentPromptCatalogProperties.Memory(
                        new AgentPromptCatalogProperties.MemoryPrompt(
                                "EXPLICIT_SYSTEM",
                                "version={promptVersion};source={sourceMessage}"),
                        new AgentPromptCatalogProperties.MemoryPrompt(
                                "IMPLICIT_SYSTEM",
                                "version={promptVersion};previous={previousMessage};current={currentMessage}"),
                        new AgentPromptCatalogProperties.MemoryPrompt(
                                "EVIDENCE_SYSTEM", "input={inputJson}")),
                new AgentPromptCatalogProperties.Context(
                        "[MEMORY_POLICY]\nconfigured policy\n[/MEMORY_POLICY]",
                        new AgentPromptCatalogProperties.SummaryContext(
                                "[CONFIGURED_SUMMARY]", "[/CONFIGURED_SUMMARY]",
                                "topic={topic};state={currentState}",
                                "configured-facts:", "configured-decisions:",
                                "configured-questions:", "configured-entities:"),
                        new AgentPromptCatalogProperties.UserMemoryContext(
                                "[CONFIGURED_MEMORY]", "[/CONFIGURED_MEMORY]",
                                "configured-memory-header",
                                "source={sourceType};category={category};content={content}")),
                new AgentPromptCatalogProperties.Knowledge(
                        "CONFIGURED_EVIDENCE_HEADER"),
                Map.copyOf(tools));
    }

    private static void put(
            Map<String, AgentPromptCatalogProperties.ToolPrompt> tools,
            String name,
            String... parameters) {
        Map<String, String> descriptions = new LinkedHashMap<>();
        for (String parameter : parameters) {
            descriptions.put(parameter, "description-" + parameter);
        }
        tools.put(name, new AgentPromptCatalogProperties.ToolPrompt(
                "description-" + name, Map.copyOf(descriptions)));
    }
}
