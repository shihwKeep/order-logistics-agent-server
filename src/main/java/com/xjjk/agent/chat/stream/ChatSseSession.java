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

    public ChatSseSession(SseEmitter emitter) {
        this.emitter = Objects.requireNonNull(emitter, "SSE 输出不能为空");
    }

    public void session(String conversationId, String requestId) throws IOException {
        send("session", new ChatStreamPayloads.Session(conversationId, requestId));
    }

    public void generating() throws IOException {
        send("status", new ChatStreamPayloads.Status("GENERATING", "正在生成回答"));
    }

    /** 卡片动作正在确定性查询物流，不表示模型正在生成。 */
    public void queryingLogistics() throws IOException {
        send("status", new ChatStreamPayloads.Status(
                "QUERYING_LOGISTICS", "正在查询物流"));
    }

    /** 客户卡片动作正在确定性查询订单，不表示模型正在生成。 */
    public void queryingCustomerOrders() throws IOException {
        send("status", new ChatStreamPayloads.Status(
                "QUERYING_CUSTOMER_ORDERS", "正在查询客户订单"));
    }

    public void delta(String text) throws IOException {
        send("delta", new ChatStreamPayloads.Delta(text));
    }

    /** 发送工具产生的结构化结果，正文仍由模型通过 delta 输出。 */
    public void result(ToolUiResult result) throws IOException {
        Objects.requireNonNull(result, "工具结果不能为空");
        result(result.kind(), result.schemaVersion(), result.queriedAt(), result.data());
    }

    /** 工具名属于服务端调用元数据，不进入前端公开的 result 负载。 */
    public void result(
            String kind,
            int schemaVersion,
            java.time.OffsetDateTime queriedAt,
            Object data) throws IOException {
        send("result", new ChatStreamPayloads.Result(
                kind, schemaVersion, queriedAt, data));
    }

    /** 仅由确认回答成功落库的收尾流程调用。 */
    public void done(String messageId) throws IOException {
        send("done", new ChatStreamPayloads.Done(messageId));
    }

    public void error(ChatStreamError error, String requestId) throws IOException {
        send("error", new ChatStreamPayloads.Error(error.code(), error.message(), requestId));
    }

    public void complete() {
        emitter.complete();
    }

    private synchronized <T> void send(String type, T payload) throws IOException {
        long nextSequence = ++sequence;
        emitter.send(SseEmitter.event()
                .name(type)
                .id(Long.toString(nextSequence))
                .data(ChatStreamEvent.of(type, nextSequence, payload), MediaType.APPLICATION_JSON));
    }
}
