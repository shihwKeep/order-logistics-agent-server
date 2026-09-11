package com.xjjk.agent.memory.api.dto;

public record MemoryMutationResponse(int affectedCount, String memoryId, Long generation) {
}
