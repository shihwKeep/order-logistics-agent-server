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

    /** customerId 只能由可信客户解析服务提供，不能作为模型工具参数。 */
    default OrderSearchResult searchByCustomerId(
            long customerId,
            AgentIdentity identity,
            String requestId) {
        throw new UnsupportedOperationException("当前订单网关不支持按客户查询");
    }

    OrderLogisticsResult logistics(
            String identifier,
            OrderIdentifierType identifierType,
            AgentIdentity identity,
            String requestId);
}
