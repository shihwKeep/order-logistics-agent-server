package com.xjjk.agent.order.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

/** order 服务的 Agent 按客户查询订单接口。 */
@FeignClient(
        name = "order-agent-customer-search",
        url = "${integration.order.base-url}",
        configuration = OrderFeignConfiguration.class)
public interface OrderCustomerClient {

    @PostMapping("/internal/agent/orders/by-customer")
    OrderServiceResponse<OrderSearchClient.OrderSearchData> searchByCustomer(
            @RequestHeader("X-Agent-Internal-Token") String internalToken,
            @RequestHeader("X-Agent-Tenant-Id") long tenantId,
            @RequestHeader("X-Agent-User-Id") long userId,
            @RequestHeader("X-Agent-Org-Id") long orgId,
            @RequestHeader("X-Agent-Request-Id") String requestId,
            @RequestBody CustomerOrderRequest request);

    /** customerId 只允许由 Agent 的可信客户解析链路产生。 */
    record CustomerOrderRequest(long customerId) {
    }
}
