package com.xjjk.agent.customer.service;

import com.xjjk.agent.order.domain.OrderSearchResult;

/**
 * 客户订单查询的安全结果。
 *
 * <p>内部 customerId 在服务调用结束后立即丢弃，既不进入模型文本，也不进入 SSE 数据。</p>
 */
public record CustomerOrderQueryResult(
        CustomerOrderResolution resolution,
        String customerCode,
        String customerDisplayName,
        OrderSearchResult orders) {
}
