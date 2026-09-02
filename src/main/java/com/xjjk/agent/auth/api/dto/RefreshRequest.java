package com.xjjk.agent.auth.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RefreshRequest(
        @NotBlank @Size(max = 4096) String refreshToken
) {
}
