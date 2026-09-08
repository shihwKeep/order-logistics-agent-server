package com.xjjk.agent.customer.service;

/** 客户编号解析后是否可以安全进入订单查询。 */
public enum CustomerOrderResolution {
    FOUND,
    NOT_FOUND,
    AMBIGUOUS
}
