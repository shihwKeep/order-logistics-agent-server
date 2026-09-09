package com.xjjk.agent.chat.service.stream;

import com.xjjk.agent.chat.result.PendingMessageResult;
import com.xjjk.agent.tool.ToolUiResult;

import java.util.Objects;

/** 尚未通过本轮新鲜性校验的结构化结果。 */
record StagedToolResult(PendingMessageResult pending, ToolUiResult uiResult) {

    StagedToolResult {
        Objects.requireNonNull(pending, "待持久化结果不能为空");
        Objects.requireNonNull(uiResult, "SSE 工具结果不能为空");
        if (!pending.kind().equals(uiResult.kind())
                || pending.schemaVersion() != uiResult.schemaVersion()) {
            throw new IllegalArgumentException("暂存结果协议不一致");
        }
    }
}
