package com.xjjk.agent.tool;

import java.time.OffsetDateTime;

/**
 * 工具面向前端输出的完整结构化查询快照。
 *
 * <p>模型只接收工具返回的压缩文本；卡片、时间线等完整数据通过该对象走 SSE。</p>
 *
 * @param toolName 产生结果的稳定工具名
 * @param kind 前端选择卡片渲染器使用的结果类型
 * @param schemaVersion 结构化载荷协议版本
 * @param queriedAt 业务系统实际查询时间
 * @param data 已脱敏并经过下游响应校验的完整卡片数据
 */
public record ToolUiResult(
        String toolName,
        String kind,
        int schemaVersion,
        OffsetDateTime queriedAt,
        Object data) {
}
