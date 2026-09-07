package com.xjjk.agent.tool;

import com.xjjk.agent.identity.domain.AgentIdentity;

/**
 * 单轮模型工具调用的可信服务端上下文。
 *
 * <p>该对象只通过 Spring AI {@code ToolContext} 传递，不进入提示词，
 * 模型无法伪造请求身份、输出通道或调用次数限制。</p>
 */
public record AgentToolRequestContext(
        String requestId,
        AgentIdentity identity,
        ToolOutputPublisher outputPublisher,
        ToolCallGuard callGuard) {
    public static final String CONTEXT_KEY = "agentToolRequestContext";
}
