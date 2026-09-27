package com.xjjk.agent.chat.replay;

import com.xjjk.agent.chat.api.dto.ChatStreamPayloads;
import com.xjjk.agent.chat.observation.ChatStreamReplayMetrics;
import com.xjjk.agent.chat.stream.ChatEventPublisher;
import com.xjjk.agent.chat.stream.ChatStreamError;
import com.xjjk.agent.identity.domain.AgentIdentity;
import com.xjjk.agent.tool.ToolUiResult;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * 将业务事件写入 Redis 回放流的发布器。
 *
 * <p>它不持有 {@code SseEmitter}，因此浏览器或 Electron 的连接断开不会
 * 影响生产任务。SSE 转发器只读取这里持久化的事件。</p>
 */
public final class ReplayChatEventPublisher implements ChatEventPublisher {

    private final ChatReplayRepository repository;
    private final AgentIdentity identity;
    private final String requestId;
    private final Instant taskExpiresAt;
    private boolean sessionSent;
    private boolean terminal;
    private ChatStreamReplayMetrics metrics;

    public ReplayChatEventPublisher(
            ChatReplayRepository repository,
            AgentIdentity identity,
            String requestId
    ) {
        this(repository, identity, requestId, null);
    }

    public ReplayChatEventPublisher(
            ChatReplayRepository repository,
            AgentIdentity identity,
            String requestId,
            Instant taskExpiresAt
    ) {
        this.repository = Objects.requireNonNull(repository, "回放仓储不能为空");
        this.identity = Objects.requireNonNull(identity, "认证身份不能为空");
        this.requestId = Objects.requireNonNull(requestId, "请求 ID 不能为空");
        this.taskExpiresAt = taskExpiresAt;
    }

    @Override
    public boolean detached() {
        return true;
    }

    @Override
    public synchronized void session(
            String conversationId,
            String eventRequestId,
            Instant expiresAt,
            boolean resumable
    ) throws IOException {
        if (sessionSent || terminal) {
            return;
        }
        if (!requestId.equals(eventRequestId)) {
            throw new IllegalArgumentException("事件请求 ID 与回放任务不一致");
        }
        // 只有完成 MySQL 会话归属校验和本轮开始事务后，才绑定可信会话 ID。
        repository.bindConversation(identity, requestId, conversationId);
        Instant effectiveExpiresAt = taskExpiresAt != null ? taskExpiresAt : expiresAt;
        append("session", new ChatStreamPayloads.Session(
                conversationId, requestId, effectiveExpiresAt, true));
        sessionSent = true;
    }

    @Override
    public synchronized boolean heartbeat() throws IOException {
        if (!sessionSent || terminal) {
            return false;
        }
        append("heartbeat", new ChatStreamPayloads.Heartbeat());
        return true;
    }

    @Override
    public synchronized void generating() throws IOException {
        appendNonTerminal("status", new ChatStreamPayloads.Status(
                "GENERATING", "正在生成回答"));
    }

    @Override
    public synchronized void queryingLogistics() throws IOException {
        appendNonTerminal("status", new ChatStreamPayloads.Status(
                "QUERYING_LOGISTICS", "正在查询物流"));
    }

    @Override
    public synchronized void queryingCustomerOrders() throws IOException {
        appendNonTerminal("status", new ChatStreamPayloads.Status(
                "QUERYING_CUSTOMER_ORDERS", "正在查询客户订单"));
    }

    @Override
    public synchronized void queryingAfterSaleDetail() throws IOException {
        appendNonTerminal("status", new ChatStreamPayloads.Status(
                "QUERYING_AFTER_SALE_DETAIL", "正在查询售后详情"));
    }

    @Override
    public synchronized void delta(String text) throws IOException {
        appendNonTerminal("delta", new ChatStreamPayloads.Delta(text));
    }

    @Override
    public synchronized void result(ToolUiResult result) throws IOException {
        Objects.requireNonNull(result, "工具结果不能为空");
        result(result.kind(), result.schemaVersion(), result.queriedAt(), result.data());
    }

    @Override
    public synchronized void result(
            String kind,
            int schemaVersion,
            OffsetDateTime queriedAt,
            Object data
    ) throws IOException {
        appendNonTerminal("result", new ChatStreamPayloads.Result(
                kind, schemaVersion, queriedAt, data));
    }

    @Override
    public synchronized void done(String messageId) throws IOException {
        if (terminal) {
            recordDuplicateTerminal("done");
            return;
        }
        append("done", new ChatStreamPayloads.Done(messageId));
        terminal = true;
        recordTerminal("done");
    }

    @Override
    public synchronized void error(ChatStreamError error, String eventRequestId)
            throws IOException {
        if (terminal) {
            recordDuplicateTerminal("error");
            return;
        }
        if (!requestId.equals(eventRequestId)) {
            throw new IllegalArgumentException("事件请求 ID 与回放任务不一致");
        }
        append("error", new ChatStreamPayloads.Error(
                error.code(), error.message(), requestId));
        terminal = true;
        recordTerminal("error");
    }

    /** 生产任务结束不等于删除回放数据；回放数据由 Redis TTL 清理。 */
    @Override
    public void complete() {
        // 无网络资源需要关闭。
    }

    public void metrics(ChatStreamReplayMetrics metrics) {
        this.metrics = metrics;
    }

    private void recordTerminal(String type) {
        if (metrics != null) metrics.terminal(type);
    }

    private void recordDuplicateTerminal(String type) {
        if (metrics != null) metrics.duplicateTerminal(type);
    }

    private void appendNonTerminal(String type, Object payload) {
        if (!terminal) {
            append(type, payload);
        }
    }

    private void append(String type, Object payload) {
        repository.append(identity, requestId, type, payload);
    }
}
