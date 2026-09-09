package com.xjjk.agent.chat.result;

import java.time.OffsetDateTime;

/**
 * SSE 发布前完成序列化与大小校验、等待聊天收尾事务持久化的不可变结果。
 *
 * <p>归属字段不放在该对象中，聊天收尾事务必须从可信 ChatTurnContext 覆盖写入。</p>
 *
 * @param resultSequence 同一助手消息内从 1 开始的结果顺序
 * @param toolName 产生结果的工具名
 * @param kind 前端结构化结果类型
 * @param schemaVersion 载荷协议版本
 * @param payloadJson 已序列化的完整 UI 载荷
 * @param payloadBytes UTF-8 字节数
 * @param queriedAt 业务查询时间
 */
public record PendingMessageResult(
        int resultSequence,
        String toolName,
        String kind,
        int schemaVersion,
        String payloadJson,
        int payloadBytes,
        OffsetDateTime queriedAt) {

    /** 日志只能看到受控元数据，禁止意外输出订单或物流 JSON 正文。 */
    @Override
    public String toString() {
        return "PendingMessageResult["
                + "resultSequence=" + resultSequence
                + ", toolName=" + toolName
                + ", kind=" + kind
                + ", schemaVersion=" + schemaVersion
                + ", payloadBytes=" + payloadBytes
                + ", queriedAt=" + queriedAt
                + ']';
    }
}
