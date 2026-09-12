package com.xjjk.agent.memory.recall;

/** 用户记忆召回的受控可用性状态，不包含候选正文或内部异常。 */
public enum UserMemoryRecallStatus {
    AVAILABLE,
    NOT_INITIALIZED,
    DISABLED,
    INVALID_REQUEST,
    UNAVAILABLE
}
