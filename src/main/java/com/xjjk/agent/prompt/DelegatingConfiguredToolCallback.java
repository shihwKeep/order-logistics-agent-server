package com.xjjk.agent.prompt;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.Objects;

/** 仅替换模型可见的工具定义，执行仍委托给 Spring AI 生成的原始回调。 */
final class DelegatingConfiguredToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final ToolDefinition definition;

    DelegatingConfiguredToolCallback(
            ToolCallback delegate,
            ToolDefinition definition) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.definition = Objects.requireNonNull(definition, "definition");
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return delegate.call(toolInput);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return delegate.call(toolInput, toolContext);
    }
}
