package com.xjjk.agent.aftersale.service;

import com.xjjk.agent.aftersale.domain.AfterSaleDetailResult;
import com.xjjk.agent.aftersale.domain.AfterSaleIdentifierType;
import com.xjjk.agent.aftersale.domain.AfterSaleSearchResult;
import com.xjjk.agent.identity.domain.AgentIdentity;

import java.time.OffsetDateTime;

/** Agent 对售后服务的只读查询边界，调用方只能传外部业务编号和可信坐席身份。 */
public interface AfterSaleQueryGateway {
    /** 按指定业务标识和可选创建时间范围查询脱敏售后工单列表。 */
    AfterSaleSearchResult search(
            AfterSaleIdentifierType type,
            String identifier,
            OffsetDateTime startTime,
            OffsetDateTime endTime,
            AgentIdentity identity,
            String requestId);

    /** 按完整售后工单号查询已限制明细数量的脱敏详情。 */
    AfterSaleDetailResult detail(
            String afterSaleCode,
            AgentIdentity identity,
            String requestId);
}
