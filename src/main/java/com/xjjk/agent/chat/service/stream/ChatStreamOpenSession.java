package com.xjjk.agent.chat.service.stream;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.Objects;

/** Controller 设置恢复协议响应头所需的流式接入结果。 */
public record ChatStreamOpenSession(
        SseEmitter emitter,
        String requestId,
        Instant expiresAt,
        boolean resumable
) {
    public ChatStreamOpenSession {
        Objects.requireNonNull(emitter, "SSE 输出不能为空");
        Objects.requireNonNull(requestId, "请求 ID 不能为空");
        Objects.requireNonNull(expiresAt, "任务截止时间不能为空");
    }
}
