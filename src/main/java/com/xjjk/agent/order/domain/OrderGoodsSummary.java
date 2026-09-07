package com.xjjk.agent.order.domain;

/** 订单卡片内受限展示的商品摘要。 */
public record OrderGoodsSummary(
        String goodsName,
        String skuCode,
        String specification,
        int quantity) {
}
