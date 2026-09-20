package com.xjjk.agent.prompt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.Map;

import static com.xjjk.agent.prompt.PromptCatalogTestFixture.catalog;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfiguredToolCallbackFactoryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void replacesToolAndParameterDescriptionsAndDelegatesExecution() throws Exception {
        ConfiguredToolCallbackFactory factory = new ConfiguredToolCallbackFactory(
                objectMapper, catalogWithEcho(Map.of("value", "configured-value")));

        ToolCallback callback = factory.from(new EchoTool())[0];
        JsonNode schema = objectMapper.readTree(
                callback.getToolDefinition().inputSchema());

        assertThat(callback.getToolDefinition().name()).isEqualTo("echo");
        assertThat(callback.getToolDefinition().description())
                .isEqualTo("configured-echo");
        assertThat(schema.at("/properties/value/description").asText())
                .isEqualTo("configured-value");
        assertThat(schema.at("/required/0").asText()).isEqualTo("value");
        assertThat(callback.call("{\"value\":\"hello\"}"))
                .contains("hello");
    }

    @Test
    void rejectsParameterConfigurationThatDoesNotMatchGeneratedSchema() {
        ConfiguredToolCallbackFactory factory = new ConfiguredToolCallbackFactory(
                objectMapper, catalogWithEcho(Map.of("other", "other")));

        assertThatThrownBy(() -> factory.from(new EchoTool()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("agent.ai.prompt.catalog.tools[echo]")
                .hasMessageContaining("value");
    }

    private static AgentPromptCatalogProperties catalogWithEcho(
            Map<String, String> parameters) {
        AgentPromptCatalogProperties base = catalog();
        return new AgentPromptCatalogProperties(
                base.summary(), base.memory(), base.context(), base.knowledge(),
                Map.of("echo", new AgentPromptCatalogProperties.ToolPrompt(
                        "configured-echo", parameters)));
    }

    static class EchoTool {
        @Tool(name = "echo")
        String echo(@ToolParam String value) {
            return "echo:" + value;
        }
    }
}
