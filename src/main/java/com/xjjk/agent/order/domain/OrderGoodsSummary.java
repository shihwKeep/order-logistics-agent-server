package com.xjjk.agent.order.domain;

/** 订单卡片内受限展示的商品摘要。 */
public record OrderGoodsSummary(
        String goodsName,
        String skuCode,
        String specification,
        Long unitPriceInFen,
        int quantity,
        Long subtotalInFen,
        Boolean gift) {

    /** 兼容测试夹具和历史调用方使用的旧四字段构造方式。 */
    public OrderGoodsSummary(
            String goodsName,
            String skuCode,
            String specification,
            int quantity) {
        this(goodsName, skuCode, specification, null, quantity, null, null);
    }
}
