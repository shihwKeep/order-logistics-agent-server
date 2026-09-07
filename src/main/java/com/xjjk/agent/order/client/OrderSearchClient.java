package com.xjjk.agent.order.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.xjjk.agent.order.domain.OrderIdentifierType;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

/** order 服务的 Agent 订单精确查询接口。 */
@FeignClient(
        name = "order-agent-search",
        url = "${integration.order.base-url}",
        configuration = OrderFeignConfiguration.class)
public interface OrderSearchClient {

    @PostMapping("/internal/agent/orders/search")
    OrderServiceResponse<OrderSearchData> search(
            @RequestHeader("X-Agent-Internal-Token") String internalToken,
            @RequestHeader("X-Agent-Tenant-Id") long tenantId,
            @RequestHeader("X-Agent-User-Id") long userId,
            @RequestHeader("X-Agent-Org-Id") long orgId,
            @RequestHeader("X-Agent-Request-Id") String requestId,
            @RequestBody OrderSearchRequest request);

    /** 调用者只提交业务编号；可信身份只能由 Gateway 注入请求头。 */
    record OrderSearchRequest(String identifier, OrderIdentifierType identifierType) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OrderSearchData(
            String matchedBy,
            Long total,
            Boolean truncated,
            OffsetDateTime queriedAt,
            List<OrderCardData> items) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OrderCardData(
            String orderCode,
            String outerOrderCode,
            Integer statusCode,
            String statusText,
            String orderTime,
            String customerDisplayName,
            Long payAmountInFen,
            Integer goodsTotalCount,
            List<OrderGoodsData> goods,
            String carrierName,
            List<String> logisticsCodes) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OrderGoodsData(
            String goodsName,
            String skuCode,
            String specification,
            Integer quantity) {
    }
}
