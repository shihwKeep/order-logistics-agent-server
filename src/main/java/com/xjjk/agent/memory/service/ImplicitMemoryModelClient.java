package com.xjjk.agent.memory.service;

import com.xjjk.agent.memory.domain.ImplicitMemoryCandidate;
import com.xjjk.agent.memory.domain.MemoryExtractionDecision;

import java.util.List;

public interface ImplicitMemoryModelClient {
    MemoryExtractionDecision analyze(Request request);

    List<ImplicitMemoryCandidate> extract(Request request);

    record Request(String requestId, String userMessage, String priorUserMessage) {
    }
}
