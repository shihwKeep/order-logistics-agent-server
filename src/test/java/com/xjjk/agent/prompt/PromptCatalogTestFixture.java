package com.xjjk.agent.prompt;

import java.util.LinkedHashMap;
import java.util.Map;

public final class PromptCatalogTestFixture {

    private PromptCatalogTestFixture() {
    }

    public static AgentPromptCatalogProperties catalog() {
        Map<String, AgentPromptCatalogProperties.ToolPrompt> tools =
                new LinkedHashMap<>();
        for (String name : new String[]{
                "search_products", "search_orders", "get_order_logistics",
                "search_customers", "list_customer_orders",
                "search_after_sales", "get_after_sale_detail",
                "search_knowledge"}) {
            tools.put(name, new AgentPromptCatalogProperties.ToolPrompt(
                    "description-" + name, Map.of("value", "value")));
        }
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
}
