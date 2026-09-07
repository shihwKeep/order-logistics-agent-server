package com.xjjk.agent.order.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.xjjk.agent.order.domain.OrderIdentifierType;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

/** order 服务的 Agent 订单物流查询接口。 */
@FeignClient(
        name = "order-agent-logistics",
        url = "${integration.order.base-url}",
        configuration = OrderFeignConfiguration.class)
public interface OrderLogisticsClient {

    @PostMapping("/internal/agent/orders/logistics")
    OrderServiceResponse<OrderLogisticsData> logistics(
            @RequestHeader("X-Agent-Internal-Token") String internalToken,
            @RequestHeader("X-Agent-Tenant-Id") long tenantId,
            @RequestHeader("X-Agent-User-Id") long userId,
            @RequestHeader("X-Agent-Org-Id") long orgId,
            @RequestHeader("X-Agent-Request-Id") String requestId,
            @RequestBody OrderLogisticsRequest request);

    /** 禁止通过内部 orderId 查询，避免绕过订单定位与授权。 */
    record OrderLogisticsRequest(String identifier, OrderIdentifierType identifierType) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OrderLogisticsData(
            OrderSummaryData order,
            OffsetDateTime queriedAt,
            Boolean partial,
            List<ShipmentData> shipments) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OrderSummaryData(String orderCode, Integer statusCode, String statusText) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ShipmentData(
            String logisticsCode,
            String carrierName,
            String resultStatus,
            String latestStatusText,
            String latestTrace,
            List<TrackNodeData> traces) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TrackNodeData(String time, String location, String description) {
    }
}
