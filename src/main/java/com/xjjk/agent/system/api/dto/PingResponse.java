package com.xjjk.agent.system.api.dto;

public record PingResponse(
        String status,
        String application,
        long tenantId
) {
}
