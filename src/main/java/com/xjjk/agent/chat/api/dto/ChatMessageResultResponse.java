package com.xjjk.agent.chat.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;

/**
 * 历史消息携带的结构化工具结果。
 *
 * @param resultSequence 同一助手消息内从 1 开始的结果序号
 * @param kind 前端选择展示组件使用的结果类型
 * @param schemaVersion 结构化结果协议版本
 * @param queriedAt 业务数据查询时间，展示时转换为上海时区
 * @param data 已脱敏并经过大小校验的业务结果快照
 */
public record ChatMessageResultResponse(
        int resultSequence,
        String kind,
        int schemaVersion,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        OffsetDateTime queriedAt,
        JsonNode data
) {
}
