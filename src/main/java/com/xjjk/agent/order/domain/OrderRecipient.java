package com.xjjk.agent.order.domain;

/** 由订单服务完成脱敏后的收货信息。 */
public record OrderRecipient(
        String nameMasked,
        String phoneMasked,
        String regionText) {
}
