package com.xjjk.agent.order.domain;

import java.util.List;

/** 面向坐席和模型的脱敏订单卡片，不包含内部订单主键、手机号和地址。 */
public record OrderCard(
        String orderCode,
        String outerOrderCode,
        int statusCode,
        String statusText,
        String orderTime,
        String customerDisplayName,
        long payAmountInFen,
        int goodsTotalCount,
        List<OrderGoodsSummary> goods,
        String carrierName,
        List<String> logisticsCodes,
        Long paymentMethodCode,
        String paymentMethodText,
        OrderAmount amount,
        OrderRecipient recipient,
        Integer goodsLineCount,
        Boolean goodsTruncated,
        Integer shipmentCount,
        Boolean shipmentsTruncated,
        List<OrderShipmentSummary> shipments) {

    public OrderCard {
        goods = goods == null ? List.of() : List.copyOf(goods);
        logisticsCodes = logisticsCodes == null ? List.of() : List.copyOf(logisticsCodes);
        shipments = shipments == null ? null : List.copyOf(shipments);
    }

    /** 旧卡片构造方式继续可用，新增字段保持为空以触发前端兼容分支。 */
    public OrderCard(
            String orderCode,
            String outerOrderCode,
            int statusCode,
            String statusText,
            String orderTime,
            String customerDisplayName,
            long payAmountInFen,
            int goodsTotalCount,
            List<OrderGoodsSummary> goods,
            String carrierName,
            List<String> logisticsCodes) {
        this(orderCode, outerOrderCode, statusCode, statusText, orderTime,
                customerDisplayName, payAmountInFen, goodsTotalCount, goods,
                carrierName, logisticsCodes, null, null, null, null,
                null, null, null, null, null);
    }
}
