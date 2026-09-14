package com.xjjk.agent.chat.api.dto;

/** 显式取消接口的幂等结果。 */
public record ChatStreamCancelResponse(
        String requestId,
        boolean cancellationRequested
) {
}
