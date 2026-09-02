package com.xjjk.agent.system.api.dto;

public record CurrentIdentityResponse(
        long userId,
        String account,
        String name,
        long orgId,
        long companyId
) {
}
