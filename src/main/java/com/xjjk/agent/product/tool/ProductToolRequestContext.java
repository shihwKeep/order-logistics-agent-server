package com.xjjk.agent.product.tool;

import com.xjjk.agent.identity.domain.AgentIdentity;

/**
 * 当前商品工具调用的服务端上下文。
 *
 * <p>该对象通过 Spring AI ToolContext 传递，不进入模型提示词；模型不能伪造
 * requestId、坐席身份或 SSE 发布器。</p>
 */
public record ProductToolRequestContext(
        String requestId,
        AgentIdentity identity,
        ProductResultPublisher resultPublisher) {
    public static final String CONTEXT_KEY = "productToolRequestContext";
}
