package com.xjjk.agent.memory.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;

public record UpdateUserMemorySettingRequest(
        Boolean memoryEnabled,
        Boolean autoExtractEnabled
) {

    @JsonIgnore
    @AssertTrue(message = "至少提供一个记忆设置")
    public boolean isUpdatePresent() {
        return memoryEnabled != null || autoExtractEnabled != null;
    }
}
