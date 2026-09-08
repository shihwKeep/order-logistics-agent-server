package com.xjjk.agent.customer.service;

import com.xjjk.agent.customer.domain.CustomerMatchType;
import com.xjjk.agent.customer.domain.CustomerSearchResult;
import com.xjjk.agent.identity.domain.AgentIdentity;

/** Agent 对客户服务的只读边界。 */
public interface CustomerQueryGateway {
    CustomerSearchResult search(String keyword,
                                CustomerMatchType matchType,
                                AgentIdentity identity,
                                String requestId);
}
