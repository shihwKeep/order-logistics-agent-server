package com.xjjk.agent.chat.domain.summary;

/** 持久化摘要任务的运行状态。 */
public enum ChatSummaryTaskStatus {
    IDLE,
    PENDING,
    PROCESSING,
    RETRY,
    DEAD
}
