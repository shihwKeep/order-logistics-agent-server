package com.xjjk.agent.aftersale.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.xjjk.agent.aftersale.domain.AfterSaleIdentifierType;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import java.time.OffsetDateTime;
import java.util.List;

@FeignClient(
        name = "agent-after-sale",
        // 未下发 Nacos 配置时指向不可用端口，保证默认关闭的工具不会阻塞应用启动。
        url = "${integration.aftersale.base-url:http://127.0.0.1:9}",
        configuration = AfterSaleFeignConfiguration.class)
public interface AfterSaleClient {
    @PostMapping("/internal/agent/after-sales/search")
    AfterSaleServiceResponse<SearchData> search(
            @RequestHeader("X-Agent-Internal-Token") String token,
            @RequestHeader("X-Agent-Tenant-Id") long tenantId,
            @RequestHeader("X-Agent-User-Id") long userId,
            @RequestHeader("X-Agent-Org-Id") long orgId,
            @RequestHeader("X-Agent-Request-Id") String requestId,
            @RequestBody SearchRequest request);

    @PostMapping("/internal/agent/after-sales/detail")
    AfterSaleServiceResponse<DetailData> detail(
            @RequestHeader("X-Agent-Internal-Token") String token,
            @RequestHeader("X-Agent-Tenant-Id") long tenantId,
            @RequestHeader("X-Agent-User-Id") long userId,
            @RequestHeader("X-Agent-Org-Id") long orgId,
            @RequestHeader("X-Agent-Request-Id") String requestId,
            @RequestBody DetailRequest request);

    record SearchRequest(
            AfterSaleIdentifierType identifierType,
            String identifier,
            OffsetDateTime startTime,
            OffsetDateTime endTime) {
    }

    record DetailRequest(String afterSaleCode) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SearchData(
            String matchedBy,
            Long total,
            Boolean truncated,
            OffsetDateTime queriedAt,
            List<SearchItemData> items) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SearchItemData(
            String afterSaleCode,
            Integer statusCode,
            String statusText,
            OffsetDateTime createdAt,
            Boolean finished,
            OffsetDateTime finishedAt,
            String customerDisplayName,
            String customerCode,
            String orderCode,
            String returnLogisticsCode,
            String assigneeDisplayName) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DetailData(
            String afterSaleCode,
            Integer statusCode,
            String statusText,
            OffsetDateTime createdAt,
            Boolean finished,
            OffsetDateTime finishedAt,
            String customerDisplayName,
            String customerCode,
            String orderCode,
            String exchangeOrderCode,
            String returnLogisticsCode,
            String returnRemark,
            List<ItemData> items,
            List<ExchangeGoodsData> exchangeGoods,
            RefundSummaryData refundSummary,
            Boolean itemsTruncated,
            Boolean exchangeGoodsTruncated,
            OffsetDateTime queriedAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ItemData(
            String goodsName,
            String skuCode,
            String specification,
            String reason,
            Integer originalQuantity,
            Integer receivedQuantity,
            Integer refundQuantity,
            Integer exchangeQuantity,
            Integer sentBackQuantity) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ExchangeGoodsData(
            String goodsName,
            String skuCode,
            Integer quantity,
            Long unitPriceInFen,
            Long subtotalInFen) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RefundSummaryData(
            Long totalCashInFen,
            Long totalPreStorageInFen,
            Long returnPreStorageInFen,
            Long returnCouponInFen,
            Long returnIntegral) {
    }
}
