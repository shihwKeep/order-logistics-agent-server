package com.xjjk.agent.order.domain;

import java.time.OffsetDateTime;
import java.util.List;

/** 经过订单服务权限过滤后的订单查询结果。 */
public record OrderSearchResult(
        OrderIdentifierType matchedBy,
        long total,
        boolean truncated,
        OffsetDateTime queriedAt,
        List<OrderCard> items) {

    public OrderSearchResult {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
