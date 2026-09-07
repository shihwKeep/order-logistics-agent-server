package com.xjjk.agent.order.service;

import com.xjjk.agent.identity.domain.AgentIdentity;
import com.xjjk.agent.order.domain.OrderIdentifierType;
import com.xjjk.agent.order.domain.OrderLogisticsResult;
import com.xjjk.agent.order.domain.OrderSearchResult;

/** Agent 对订单服务的只读查询边界。 */
public interface OrderQueryGateway {

    OrderSearchResult search(
            String identifier,
            OrderIdentifierType identifierType,
            AgentIdentity identity,
            String requestId);

    OrderLogisticsResult logistics(
            String identifier,
            OrderIdentifierType identifierType,
            AgentIdentity identity,
            String requestId);
}
