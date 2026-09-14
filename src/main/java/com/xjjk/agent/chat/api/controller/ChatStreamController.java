package com.xjjk.agent.chat.api.controller;

import com.xjjk.agent.chat.api.dto.ChatStreamRequest;
import com.xjjk.agent.chat.api.dto.ChatStreamCancelResponse;
import com.xjjk.agent.chat.api.dto.ChatStreamStatusResponse;
import com.xjjk.agent.chat.config.ChatStreamProperties;
import com.xjjk.agent.chat.service.stream.ChatSseRelayService;
import com.xjjk.agent.chat.service.stream.ChatStreamOpenSession;
import com.xjjk.agent.chat.service.stream.ChatStreamService;
import com.xjjk.agent.identity.domain.AgentIdentity;
import com.xjjk.agent.identity.web.CurrentAgentIdentity;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * 聊天流式 HTTP 入口，只负责参数校验、可信身份接收和响应头。
 * 异步执行、上下文准备、模型生成和业务收尾交给应用服务。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/chat")
public class ChatStreamController {

    private final ChatStreamService streamService;
    private final ChatSseRelayService relayService;
    private final ChatStreamProperties properties;

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @Valid @RequestBody ChatStreamRequest request,
            @CurrentAgentIdentity AgentIdentity identity,
            HttpServletResponse response
    ) throws IOException {
        ChatStreamOpenSession opened = streamService.open(request, identity);
        streamHeaders(response, opened.requestId(), opened.expiresAt(), opened.resumable());
        return opened.emitter();
    }

    @GetMapping(
            value = "/stream/{requestId}/resume",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter resume(
            @PathVariable String requestId,
            @RequestParam(defaultValue = "0") long afterSequence,
            @CurrentAgentIdentity AgentIdentity identity,
            HttpServletResponse response
    ) {
        ChatStreamStatusResponse status = relayService.status(identity, requestId);
        SseEmitter emitter = relayService.resume(identity, requestId, afterSequence);
        streamHeaders(response, requestId, status.expiresAt(), true);
        return emitter;
    }

    @GetMapping("/stream/{requestId}/status")
    public ChatStreamStatusResponse status(
            @PathVariable String requestId,
            @CurrentAgentIdentity AgentIdentity identity
    ) {
        return relayService.status(identity, requestId);
    }

    @PostMapping("/stream/{requestId}/cancel")
    public ChatStreamCancelResponse cancel(
            @PathVariable String requestId,
            @CurrentAgentIdentity AgentIdentity identity
    ) {
        return streamService.cancel(identity, requestId);
    }

    private void streamHeaders(
            HttpServletResponse response,
            String requestId,
            java.time.Instant expiresAt,
            boolean resumable
    ) {
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("X-Chat-Request-Id", requestId);
        response.setHeader("X-Chat-Task-Expires-At", expiresAt.toString());
        response.setHeader("X-Chat-Resumable", Boolean.toString(resumable));
        response.setHeader("X-Chat-Reconnect-Max-Attempts",
                Integer.toString(properties.reconnect().maxAttempts()));
        response.setHeader("X-Chat-Reconnect-Initial-Backoff-Ms",
                Long.toString(properties.reconnect().initialBackoff().toMillis()));
        response.setHeader("X-Chat-Reconnect-Max-Backoff-Ms",
                Long.toString(properties.reconnect().maxBackoff().toMillis()));
        response.setHeader("X-Chat-Reconnect-Jitter-Ratio",
                Double.toString(properties.reconnect().jitterRatio()));
    }
}
