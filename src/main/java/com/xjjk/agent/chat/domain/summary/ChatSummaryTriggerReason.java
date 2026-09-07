package com.xjjk.agent.chat.domain.summary;

/** 摘要生成的低基数触发原因，可安全用于指标标签。 */
public enum ChatSummaryTriggerReason {
    NONE,
    TOKEN_THRESHOLD,
    TURN_THRESHOLD,
    SCAN_LIMIT,
    RAW_CONTEXT_PRESSURE,
    BACKLOG_CONTINUATION
}
