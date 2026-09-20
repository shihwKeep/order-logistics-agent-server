package com.xjjk.agent.prompt;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.Map;

/** Nacos 中所有模型提示词和工具描述的不可变配置目录。 */
@Validated
@ConfigurationProperties(prefix = "agent.ai.prompt.catalog")
public record AgentPromptCatalogProperties(
        @NotNull @Valid Summary summary,
        @NotNull @Valid Memory memory,
        @NotNull @Valid Context context,
        @NotNull @Valid Knowledge knowledge,
        @NotEmpty Map<String, @Valid ToolPrompt> tools) {

    public record Summary(
            @NotBlank String system,
            @NotBlank String userTemplate) {
    }

    public record Memory(
            @NotNull @Valid MemoryPrompt explicit,
            @NotNull @Valid MemoryPrompt implicit,
            @NotNull @Valid MemoryPrompt evidence) {
    }

    public record MemoryPrompt(
            @NotBlank String system,
            @NotBlank String userTemplate) {
    }

    public record Context(
            @NotBlank String systemPolicy,
            @NotNull @Valid SummaryContext summary,
            @NotNull @Valid UserMemoryContext userMemory) {
    }

    public record SummaryContext(
            @NotBlank String openMarker,
            @NotBlank String closeMarker,
            @NotBlank String headerTemplate,
            @NotBlank String factHeading,
            @NotBlank String decisionHeading,
            @NotBlank String openQuestionHeading,
            @NotBlank String entityHeading) {
    }

    public record UserMemoryContext(
            @NotBlank String openMarker,
            @NotBlank String closeMarker,
            @NotBlank String header,
            @NotBlank String entryTemplate) {
    }

    public record Knowledge(@NotBlank String evidenceHeader) {
    }

    public record ToolPrompt(
            @NotBlank String description,
            @NotEmpty Map<String, @NotBlank String> parameters) {
    }
}
