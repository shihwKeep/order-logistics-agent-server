package com.xjjk.agent.memory.domain;

public enum MemoryExtractionTaskStatus {
    PENDING,
    PROCESSING,
    RETRY,
    DONE,
    CANCELLED,
    DEAD
}
