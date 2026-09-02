package com.xjjk.agent.auth.api.dto;

public record AuthenticatedUserResponse(
        long userId,
        String account,
        String name,
        long orgId,
        long tenantId
) {
}
