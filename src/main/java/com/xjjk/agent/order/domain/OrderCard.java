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
        List<String> logisticsCodes) {

    public OrderCard {
        goods = goods == null ? List.of() : List.copyOf(goods);
        logisticsCodes = logisticsCodes == null ? List.of() : List.copyOf(logisticsCodes);
    }
}
