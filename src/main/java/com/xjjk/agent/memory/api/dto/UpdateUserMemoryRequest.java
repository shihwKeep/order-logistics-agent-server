package com.xjjk.agent.memory.api.dto;

import com.xjjk.agent.memory.domain.MemoryRetentionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateUserMemoryRequest(
        @NotBlank @Size(max = 512) String content,
        @NotNull MemoryRetentionType retentionType
) {
}
