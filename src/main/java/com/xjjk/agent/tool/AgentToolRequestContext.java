package com.xjjk.agent.tool;

import com.xjjk.agent.identity.domain.AgentIdentity;

/**
 * 单轮模型工具调用的可信服务端上下文。
 *
 * <p>该对象只通过 Spring AI {@code ToolContext} 传递，不进入提示词，
 * 模型无法伪造请求身份、输出通道或调用次数限制。</p>
 *
 * @param requestId 当前聊天轮次的服务端请求标识
 * @param identity 已认证的租户、用户和组织身份
 * @param outputPublisher 完整结构化结果的服务端 SSE 发布入口
 * @param callGuard 当前轮次的工具去重和不同调用数量保护器
 */
public record AgentToolRequestContext(
        String requestId,
        AgentIdentity identity,
        ToolOutputPublisher outputPublisher,
        ToolCallGuard callGuard) {
    /** Spring AI ToolContext 中保存本对象的固定键，不接受模型动态指定。 */
    public static final String CONTEXT_KEY = "agentToolRequestContext";
}
