package com.xjjk.agent.memory.api.dto;

import jakarta.validation.constraints.NotNull;

public record UpdateUserMemorySettingRequest(@NotNull Boolean autoExtractEnabled) {
}
