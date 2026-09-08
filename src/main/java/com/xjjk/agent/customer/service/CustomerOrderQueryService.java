package com.xjjk.agent.customer.service;

import com.xjjk.agent.customer.domain.CustomerMatchType;
import com.xjjk.agent.customer.domain.CustomerSearchItem;
import com.xjjk.agent.customer.domain.CustomerSearchResult;
import com.xjjk.agent.identity.domain.AgentIdentity;
import com.xjjk.agent.order.domain.OrderSearchResult;
import com.xjjk.agent.order.service.OrderQueryGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/** 先在客户权限域内解析客户编号，再用内部 ID 查询该客户的可见订单。 */
@Service
@RequiredArgsConstructor
public class CustomerOrderQueryService {
    private final CustomerQueryGateway customerGateway;
    private final OrderQueryGateway orderGateway;

    public CustomerOrderQueryResult query(
            String customerCode,
            AgentIdentity identity,
            String requestId) {
        Objects.requireNonNull(identity, "坐席身份不能为空");
        if (customerCode == null || customerCode.isBlank()) {
            throw new IllegalArgumentException("客户编号不能为空");
        }
        String normalizedCode = customerCode.strip();

        // customerId 不能由模型或前端提交，必须先用当前坐席身份到客户服务重新解析。
        CustomerSearchResult customerResult = customerGateway.search(
                normalizedCode,
                CustomerMatchType.CUSTOMER_CODE,
                identity,
                requestId);
        List<CustomerSearchItem> matches = customerResult.items();
        if (matches.isEmpty() && customerResult.total() == 0) {
            return new CustomerOrderQueryResult(
                    CustomerOrderResolution.NOT_FOUND, normalizedCode, null, null);
        }
        if (customerResult.truncated()
                || customerResult.total() != 1
                || matches.size() != 1) {
            return new CustomerOrderQueryResult(
                    CustomerOrderResolution.AMBIGUOUS, normalizedCode, null, null);
        }

        CustomerSearchItem customer = matches.getFirst();
        // 只在可信服务边界内短暂使用内部 ID；订单服务仍会叠加自己的组织权限条件。
        OrderSearchResult orders = orderGateway.searchByCustomerId(
                customer.customerId(), identity, requestId);
        return new CustomerOrderQueryResult(
                CustomerOrderResolution.FOUND,
                customer.customerCode(),
                customer.displayName(),
                orders);
    }

}
