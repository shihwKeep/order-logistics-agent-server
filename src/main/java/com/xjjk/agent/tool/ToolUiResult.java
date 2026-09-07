package com.xjjk.agent.tool;

import java.time.OffsetDateTime;

/**
 * 工具面向前端输出的完整结构化查询快照。
 *
 * <p>模型只接收工具返回的压缩文本；卡片、时间线等完整数据通过该对象走 SSE。</p>
 */
public record ToolUiResult(
        String toolName,
        String kind,
        int schemaVersion,
        OffsetDateTime queriedAt,
        Object data) {
}
