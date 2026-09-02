package com.xjjk.agent.auth.api.dto;

import java.time.OffsetDateTime;

public record AuthSessionResponse(
        String tokenType,
        String accessToken,
        String refreshToken,
        OffsetDateTime expiresAt,
        AuthenticatedUserResponse user
) {
}
