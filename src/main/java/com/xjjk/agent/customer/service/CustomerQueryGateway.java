package com.xjjk.agent.customer.service;

import com.xjjk.agent.customer.domain.CustomerMatchType;
import com.xjjk.agent.customer.domain.CustomerSearchResult;
import com.xjjk.agent.identity.domain.AgentIdentity;

/** Agent 对客户服务的只读边界。 */
public interface CustomerQueryGateway {
    /** 按客户编号或姓名查询当前坐席有权访问的脱敏客户列表。 */
    CustomerSearchResult search(String keyword,
                                CustomerMatchType matchType,
                                AgentIdentity identity,
                                String requestId);
}
