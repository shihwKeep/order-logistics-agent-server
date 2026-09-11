package com.xjjk.agent.memory.service;

import com.xjjk.agent.memory.domain.ImplicitMemoryCandidate;

import java.util.List;

public interface ImplicitMemoryModelClient {
    List<ImplicitMemoryCandidate> extract(Request request);

    record Request(String requestId, String userMessage, String priorUserMessage) {
    }
}
