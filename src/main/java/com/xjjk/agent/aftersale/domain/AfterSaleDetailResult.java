package com.xjjk.agent.aftersale.domain;

import java.time.OffsetDateTime;
import java.util.List;

/** 售后详情领域结果，仅包含坐席卡片允许展示的字段。 */
public record AfterSaleDetailResult(
        String afterSaleCode,
        Integer statusCode,
        String statusText,
        OffsetDateTime createdAt,
        boolean finished,
        OffsetDateTime finishedAt,
        String customerDisplayName,
        String customerCode,
        String orderCode,
        String exchangeOrderCode,
        String returnLogisticsCode,
        String returnRemark,
        List<Item> items,
        List<ExchangeGoods> exchangeGoods,
        RefundSummary refundSummary,
        boolean itemsTruncated,
        boolean exchangeGoodsTruncated,
        OffsetDateTime queriedAt) {

    public record Item(
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

    public record ExchangeGoods(
            String goodsName,
            String skuCode,
            Integer quantity,
            Long unitPriceInFen,
            Long subtotalInFen) {
    }

    public record RefundSummary(
            Long totalCashInFen,
            Long totalPreStorageInFen,
            Long returnPreStorageInFen,
            Long returnCouponInFen,
            Long returnIntegral) {
    }
}
