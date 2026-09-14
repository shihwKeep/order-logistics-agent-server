package com.xjjk.agent.chat.stream;

import com.xjjk.agent.chat.api.dto.ChatStreamEvent;
import com.xjjk.agent.chat.api.dto.ChatStreamPayloads;
import com.xjjk.agent.tool.ToolUiResult;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;

/** 将聊天事件直接写入当前 SseEmitter；该模式本身不提供断点补发。 */
public class DirectChatEventPublisher implements ChatEventPublisher {

    private final SseEmitter emitter;
    private long sequence;
    private boolean sessionSent;
    private boolean terminal;

    public DirectChatEventPublisher(SseEmitter emitter) {
        this.emitter = Objects.requireNonNull(emitter, "SSE 输出不能为空");
    }

    @Override
    public synchronized void session(String conversationId, String requestId,
                                     Instant expiresAt, boolean resumable) throws IOException {
        if (sessionSent || terminal) return;
        sendLocked("session", new ChatStreamPayloads.Session(
                conversationId, requestId, expiresAt, resumable));
        sessionSent = true;
    }

    @Override
    public synchronized boolean heartbeat() throws IOException {
        if (!sessionSent || terminal) return false;
        sendLocked("heartbeat", new ChatStreamPayloads.Heartbeat());
        return true;
    }

    @Override
    public synchronized void generating() throws IOException {
        sendLocked("status", new ChatStreamPayloads.Status("GENERATING", "正在生成回答"));
    }

    @Override
    public synchronized void queryingLogistics() throws IOException {
        sendLocked("status", new ChatStreamPayloads.Status(
                "QUERYING_LOGISTICS", "正在查询物流"));
    }

    @Override
    public synchronized void queryingCustomerOrders() throws IOException {
        sendLocked("status", new ChatStreamPayloads.Status(
                "QUERYING_CUSTOMER_ORDERS", "正在查询客户订单"));
    }

    @Override
    public synchronized void queryingAfterSaleDetail() throws IOException {
        sendLocked("status", new ChatStreamPayloads.Status(
                "QUERYING_AFTER_SALE_DETAIL", "正在查询售后详情"));
    }

    @Override
    public synchronized void delta(String text) throws IOException {
        sendLocked("delta", new ChatStreamPayloads.Delta(text));
    }

    @Override
    public synchronized void result(ToolUiResult result) throws IOException {
        Objects.requireNonNull(result, "工具结果不能为空");
        result(result.kind(), result.schemaVersion(), result.queriedAt(), result.data());
    }

    @Override
    public synchronized void result(String kind, int schemaVersion,
                                    OffsetDateTime queriedAt, Object data) throws IOException {
        sendLocked("result", new ChatStreamPayloads.Result(
                kind, schemaVersion, queriedAt, data));
    }

    @Override
    public synchronized void done(String messageId) throws IOException {
        if (terminal) return;
        terminal = true;
        sendLocked("done", new ChatStreamPayloads.Done(messageId));
    }

    @Override
    public synchronized void error(ChatStreamError error, String requestId) throws IOException {
        if (terminal) return;
        terminal = true;
        sendLocked("error", new ChatStreamPayloads.Error(
                error.code(), error.message(), requestId));
    }

    @Override
    public synchronized void complete() {
        terminal = true;
        emitter.complete();
    }

    private <T> void sendLocked(String type, T payload) throws IOException {
        if (terminal && !"done".equals(type) && !"error".equals(type)) return;
        long nextSequence = ++sequence;
        emitter.send(SseEmitter.event()
                .name(type)
                .id(Long.toString(nextSequence))
                .data(ChatStreamEvent.of(type, nextSequence, payload), MediaType.APPLICATION_JSON));
    }
}
