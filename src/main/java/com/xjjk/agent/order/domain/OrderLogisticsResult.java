package com.xjjk.agent.order.domain;

import java.time.OffsetDateTime;
import java.util.List;

/** 一个授权订单下的多运单物流查询结果。 */
public record OrderLogisticsResult(
        OrderSummary order,
        OffsetDateTime queriedAt,
        boolean partial,
        List<ShipmentTimeline> shipments) {

    public OrderLogisticsResult {
        shipments = shipments == null ? List.of() : List.copyOf(shipments);
    }

    /** 物流视图所需的最小订单信息。 */
    public record OrderSummary(String orderCode, int statusCode, String statusText) {
    }
}
