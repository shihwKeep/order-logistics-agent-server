package com.xjjk.agent.chat.service.stream;

import com.xjjk.agent.chat.result.PendingMessageResult;
import com.xjjk.agent.tool.ToolUiResult;

import java.util.Objects;

/**
 * 尚未通过本轮新鲜性校验的结构化结果。
 *
 * @param pending 已完成有界 JSON 序列化、准备在收尾事务中落库的快照
 * @param uiResult 等待通过 SSE 发布给前端的原始结构化结果
 */
record StagedToolResult(PendingMessageResult pending, ToolUiResult uiResult) {

    StagedToolResult {
        Objects.requireNonNull(pending, "待持久化结果不能为空");
        Objects.requireNonNull(uiResult, "SSE 工具结果不能为空");
        // 两条通道必须表达同一种协议，避免数据库历史与前端实时卡片不一致。
        if (!pending.kind().equals(uiResult.kind())
                || pending.schemaVersion() != uiResult.schemaVersion()) {
            throw new IllegalArgumentException("暂存结果协议不一致");
        }
    }
}
