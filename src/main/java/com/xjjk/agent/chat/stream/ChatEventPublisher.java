package com.xjjk.agent.chat.stream;

import com.xjjk.agent.tool.ToolUiResult;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;

/** 单轮聊天事件发布端口，业务执行器不感知事件的具体传输介质。 */
public interface ChatEventPublisher {

    /** 发布介质是否独立于当前客户端连接，可在断连后继续写入终态。 */
    default boolean detached() {
        return false;
    }

    void session(String conversationId, String requestId, Instant expiresAt,
                 boolean resumable) throws IOException;

    default void session(String conversationId, String requestId) throws IOException {
        session(conversationId, requestId, null, false);
    }

    boolean heartbeat() throws IOException;

    void generating() throws IOException;

    void queryingLogistics() throws IOException;

    void queryingCustomerOrders() throws IOException;

    void queryingAfterSaleDetail() throws IOException;

    void delta(String text) throws IOException;

    void result(ToolUiResult result) throws IOException;

    void result(String kind, int schemaVersion, OffsetDateTime queriedAt,
                Object data) throws IOException;

    void done(String messageId) throws IOException;

    void error(ChatStreamError error, String requestId) throws IOException;

    void complete();
}
