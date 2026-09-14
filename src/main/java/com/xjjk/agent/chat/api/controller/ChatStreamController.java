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

    /**
     * 首次发送消息并建立 SSE 连接。
     *
     * <p>{@link ChatStreamService#open(ChatStreamRequest, AgentIdentity)} 负责创建或复用
     * requestId、调度后台 Agent 任务，并根据 Redis 可用性决定是否支持断点恢复。
     * Controller 只回传本次请求的恢复元数据和 SSE 输出通道，不在 HTTP 线程中执行模型调用。</p>
     */
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

    /**
     * 网络或网关断开后的 SSE 恢复入口。
     *
     * <p>前端携带同一 requestId 和最后一个已消费的 afterSequence，服务端先校验任务归属及
     * 回放状态，再从 Redis Streams 中补发该序号之后的事件。恢复连接不会重新创建 Agent
     * 任务，也不会延长后台任务本身的最大执行时间。</p>
     */
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

    /**
     * 查询聊天任务状态。
     *
     * <p>主要用于自动重连耗尽、桌面端重启等无法继续持有 SSE 的场景，帮助前端判断本轮
     * 任务仍在运行，还是已经进入 DONE、ERROR、TIMEOUT 或 CANCELLED 终态。</p>
     */
    @GetMapping("/stream/{requestId}/status")
    public ChatStreamStatusResponse status(
            @PathVariable String requestId,
            @CurrentAgentIdentity AgentIdentity identity
    ) {
        return relayService.status(identity, requestId);
    }

    /**
     * 用户主动停止生成的入口。
     *
     * <p>主动取消和普通网络断开语义不同：网络断开只停止当前 SSE 中继，后台任务继续运行；
     * 主动取消会写入分布式取消信号，并通知本机正在运行的任务尽快终止下游模型调用。</p>
     */
    @PostMapping("/stream/{requestId}/cancel")
    public ChatStreamCancelResponse cancel(
            @PathVariable String requestId,
            @CurrentAgentIdentity AgentIdentity identity
    ) {
        // 取消前复用查询入口的租户与用户归属校验，避免仅凭 requestId 越权操作。
        relayService.status(identity, requestId);
        return streamService.cancel(identity, requestId);
    }

    /**
     * 写入客户端恢复 SSE 所需的响应头。
     *
     * <p>关闭缓存和代理缓冲可让事件及时到达前端；requestId、过期时间和 resumable 用于
     * 决定是否能够断点续拉；重连次数、退避时间及抖动比例由后端统一下发，前端仍会执行
     * 安全范围校验，避免异常配置导致无限重试。</p>
     */
    private void streamHeaders(
            HttpServletResponse response,
            String requestId,
            java.time.Instant expiresAt,
            boolean resumable
    ) {
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("X-Chat-Request-Id", requestId);
        response.setHeader("X-Chat-Expires-At", expiresAt.toString());
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
