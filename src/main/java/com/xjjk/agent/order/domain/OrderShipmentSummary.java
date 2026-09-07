package com.xjjk.agent.order.domain;

/** 增强订单卡片中的发货包裹概要。 */
public record OrderShipmentSummary(
        String carrierName,
        String logisticsCode,
        String deliveryTime) {
}
