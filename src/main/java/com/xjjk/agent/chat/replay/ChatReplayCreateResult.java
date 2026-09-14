package com.xjjk.agent.chat.replay;

/** 可恢复请求初始化结果；EXISTING 表示幂等连接已有任务。 */
public enum ChatReplayCreateResult {
    CREATED,
    EXISTING
}
