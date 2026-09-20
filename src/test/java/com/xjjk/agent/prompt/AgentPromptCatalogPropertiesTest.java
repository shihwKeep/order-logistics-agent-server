package com.xjjk.agent.prompt;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentPromptCatalogPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void startsWhenCompleteCatalogIsConfigured() {
        runner.withPropertyValues(completeProperties().toArray(String[]::new))
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void failsAtStartupWhenRequiredPromptIsMissing() {
        List<String> properties = completeProperties();
        properties.removeIf(value -> value.startsWith(
                "agent.ai.prompt.catalog.memory.implicit.system="));

        runner.withPropertyValues(properties.toArray(String[]::new))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertCauseContains(context.getStartupFailure(),
                            "agent.ai.prompt.catalog.memory.implicit.system");
                });
    }

    @Test
    void failsAtStartupWhenTemplateLacksRequiredPlaceholder() {
        List<String> properties = completeProperties();
        properties.removeIf(value -> value.startsWith(
                "agent.ai.prompt.catalog.memory.implicit.user-template="));
        properties.add("agent.ai.prompt.catalog.memory.implicit.user-template="
                + "{promptVersion}|{previousMessage}");

        runner.withPropertyValues(properties.toArray(String[]::new))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining(
                                    "agent.ai.prompt.catalog.memory.implicit.user-template")
                            .hasMessageContaining("currentMessage");
                });
    }

    @Test
    void failsAtStartupWhenUnknownToolIsConfigured() {
        List<String> properties = completeProperties();
        properties.add("agent.ai.prompt.catalog.tools.unknown-tool.description=unknown");
        properties.add("agent.ai.prompt.catalog.tools.unknown-tool.parameters.value=value");

        runner.withPropertyValues(properties.toArray(String[]::new))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("agent.ai.prompt.catalog.tools")
                            .hasMessageContaining("unknown-tool");
                });
    }

    private static List<String> completeProperties() {
        List<String> values = new ArrayList<>(List.of(
                "agent.ai.prompt.catalog.summary.system=summary-system",
                "agent.ai.prompt.catalog.summary.user-template={promptVersion}|{targetOutputTokens}|{formatCorrection}|{inputJson}",
                "agent.ai.prompt.catalog.memory.explicit.system=explicit-system",
                "agent.ai.prompt.catalog.memory.explicit.user-template={promptVersion}|{sourceMessage}",
                "agent.ai.prompt.catalog.memory.implicit.system=implicit-system",
                "agent.ai.prompt.catalog.memory.implicit.user-template={promptVersion}|{previousMessage}|{currentMessage}",
                "agent.ai.prompt.catalog.memory.evidence.system=evidence-system",
                "agent.ai.prompt.catalog.memory.evidence.user-template={inputJson}",
                "agent.ai.prompt.catalog.context.system-policy=memory-policy",
                "agent.ai.prompt.catalog.context.summary.open-marker=[SUMMARY]",
                "agent.ai.prompt.catalog.context.summary.close-marker=[/SUMMARY]",
                "agent.ai.prompt.catalog.context.summary.header-template={topic}|{currentState}",
                "agent.ai.prompt.catalog.context.summary.fact-heading=facts",
                "agent.ai.prompt.catalog.context.summary.decision-heading=decisions",
                "agent.ai.prompt.catalog.context.summary.open-question-heading=questions",
                "agent.ai.prompt.catalog.context.summary.entity-heading=entities",
                "agent.ai.prompt.catalog.context.user-memory.open-marker=[MEMORY]",
                "agent.ai.prompt.catalog.context.user-memory.close-marker=[/MEMORY]",
                "agent.ai.prompt.catalog.context.user-memory.header=memory-header",
                "agent.ai.prompt.catalog.context.user-memory.entry-template={sourceType}|{category}|{content}",
                "agent.ai.prompt.catalog.knowledge.evidence-header=evidence-header"));
        for (String tool : List.of(
                "search_products", "search_orders", "get_order_logistics",
                "search_customers", "list_customer_orders",
                "search_after_sales", "get_after_sale_detail",
                "search_knowledge")) {
            values.add("agent.ai.prompt.catalog.tools[" + tool + "]"
                    + ".description=" + tool);
            values.add("agent.ai.prompt.catalog.tools[" + tool + "]"
                    + ".parameters.value=value");
        }
        return values;
    }

    private static void assertCauseContains(Throwable failure, String fragment) {
        Throwable current = failure;
        while (current != null) {
            if (current.getMessage() != null
                    && current.getMessage().contains(fragment)) {
                return;
            }
            current = current.getCause();
        }
        throw new AssertionError("异常链中未找到文本: " + fragment, failure);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AgentPromptCatalogProperties.class)
    static class TestConfiguration {
        @Bean
        StrictPromptTemplateRenderer renderer() {
            return new StrictPromptTemplateRenderer();
        }

        @Bean
        AgentPromptCatalogValidator validator(
                AgentPromptCatalogProperties properties,
                StrictPromptTemplateRenderer renderer) {
            return new AgentPromptCatalogValidator(properties, renderer);
        }
    }
}
