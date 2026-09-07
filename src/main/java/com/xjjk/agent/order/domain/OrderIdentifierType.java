package com.xjjk.agent.order.domain;

/** 订单服务支持的精确编号匹配方式。 */
public enum OrderIdentifierType {
    AUTO,
    ORDER_CODE,
    OUTER_ORDER_CODE,
    LOGISTICS_CODE
}
