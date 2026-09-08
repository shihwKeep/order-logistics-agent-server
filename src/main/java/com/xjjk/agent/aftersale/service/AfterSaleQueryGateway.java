package com.xjjk.agent.aftersale.service;

import com.xjjk.agent.aftersale.domain.AfterSaleDetailResult;
import com.xjjk.agent.aftersale.domain.AfterSaleIdentifierType;
import com.xjjk.agent.aftersale.domain.AfterSaleSearchResult;
import com.xjjk.agent.identity.domain.AgentIdentity;

import java.time.OffsetDateTime;

public interface AfterSaleQueryGateway {
    AfterSaleSearchResult search(
            AfterSaleIdentifierType type,
            String identifier,
            OffsetDateTime startTime,
            OffsetDateTime endTime,
            AgentIdentity identity,
            String requestId);

    AfterSaleDetailResult detail(
            String afterSaleCode,
            AgentIdentity identity,
            String requestId);
}
