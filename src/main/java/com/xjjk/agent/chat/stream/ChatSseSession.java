package com.xjjk.agent.chat.stream;

import com.xjjk.agent.chat.api.dto.ChatStreamEvent;
import com.xjjk.agent.chat.api.dto.ChatStreamPayloads;
import com.xjjk.agent.tool.ToolUiResult;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Objects;

/**
 * 单个请求的 SSE 输出通道，统一事件格式与递增序号。
 * 由工作线程发送事件；任务拒绝时仅由提交线程发送。
 * 不负责取消、数据库收尾或模型业务状态。
 */
public final class ChatSseSession {

    private final SseEmitter emitter;
    private long sequence;
    private boolean sessionSent;
    private boolean terminal;

    public ChatSseSession(SseEmitter emitter) {
        this.emitter = Objects.requireNonNull(emitter, "SSE 输出不能为空");
    }

    public synchronized void session(String conversationId, String requestId) throws IOException {
        if (sessionSent || terminal) {
            return;
        }
        sendLocked("session", new ChatStreamPayloads.Session(conversationId, requestId));
        sessionSent = true;
    }

    /**
     * 在 session 事件成功写出后保持连接活跃；心跳不改变 SSE 的总生命周期。
     *
     * @return 本次是否实际写出了心跳
     */
    public synchronized boolean heartbeat() throws IOException {
        if (!sessionSent || terminal) {
            return false;
        }
        sendLocked("heartbeat", new ChatStreamPayloads.Heartbeat());
        return true;
    }

    public synchronized void generating() throws IOException {
        sendLocked("status", new ChatStreamPayloads.Status("GENERATING", "正在生成回答"));
    }

    /** 卡片动作正在确定性查询物流，不表示模型正在生成。 */
    public synchronized void queryingLogistics() throws IOException {
        sendLocked("status", new ChatStreamPayloads.Status(
                "QUERYING_LOGISTICS", "正在查询物流"));
    }

    /** 客户卡片动作正在确定性查询订单，不表示模型正在生成。 */
    public synchronized void queryingCustomerOrders() throws IOException {
        sendLocked("status", new ChatStreamPayloads.Status(
                "QUERYING_CUSTOMER_ORDERS", "正在查询客户订单"));
    }

    /** 售后卡片动作正在确定性查询详情，不表示模型正在生成。 */
    public synchronized void queryingAfterSaleDetail() throws IOException {
        sendLocked("status", new ChatStreamPayloads.Status(
                "QUERYING_AFTER_SALE_DETAIL", "正在查询售后详情"));
    }

    public synchronized void delta(String text) throws IOException {
        sendLocked("delta", new ChatStreamPayloads.Delta(text));
    }

    /** 发送工具产生的结构化结果，正文仍由模型通过 delta 输出。 */
    public synchronized void result(ToolUiResult result) throws IOException {
        Objects.requireNonNull(result, "工具结果不能为空");
        result(result.kind(), result.schemaVersion(), result.queriedAt(), result.data());
    }

    /** 工具名属于服务端调用元数据，不进入前端公开的 result 负载。 */
    public synchronized void result(
            String kind,
            int schemaVersion,
            java.time.OffsetDateTime queriedAt,
            Object data) throws IOException {
        sendLocked("result", new ChatStreamPayloads.Result(
                kind, schemaVersion, queriedAt, data));
    }

    /** 仅由确认回答成功落库的收尾流程调用。 */
    public synchronized void done(String messageId) throws IOException {
        if (terminal) {
            return;
        }
        terminal = true;
        sendLocked("done", new ChatStreamPayloads.Done(messageId));
    }

    public synchronized void error(ChatStreamError error, String requestId) throws IOException {
        if (terminal) {
            return;
        }
        terminal = true;
        sendLocked("error", new ChatStreamPayloads.Error(error.code(), error.message(), requestId));
    }

    public synchronized void complete() {
        terminal = true;
        emitter.complete();
    }

    private <T> void sendLocked(String type, T payload) throws IOException {
        if (terminal && !"done".equals(type) && !"error".equals(type)) {
            return;
        }
        long nextSequence = ++sequence;
        emitter.send(SseEmitter.event()
                .name(type)
                .id(Long.toString(nextSequence))
                .data(ChatStreamEvent.of(type, nextSequence, payload), MediaType.APPLICATION_JSON));
    }
}
