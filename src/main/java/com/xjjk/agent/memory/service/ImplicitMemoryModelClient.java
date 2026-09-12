package com.xjjk.agent.memory.service;

import com.xjjk.agent.memory.domain.MemoryExtractionDecision;

public interface ImplicitMemoryModelClient {
    MemoryExtractionDecision analyze(Request request);

    record Request(String requestId, String userMessage, String priorUserMessage) {
    }
}
