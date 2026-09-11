package com.xjjk.agent.memory.domain;

/** 不包含用户或模型正文的隐式记忆抽取完成结果。 */
public enum MemoryExtractionResultCode {
    SAVED,
    MODEL_EMPTY,
    ALL_REJECTED,
    NO_CHANGE,
    MODEL_PROTOCOL_REJECTED
}
