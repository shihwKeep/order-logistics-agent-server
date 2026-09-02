package com.xjjk.agent.identity.domain;

public record AgentIdentity(
        long userId,
        String account,
        String name,
        long orgId,
        long tenantId
) {
}
