package com.xjjk.agent.order.domain;

import java.util.List;

/** 单个运单的独立轨迹时间线。 */
public record ShipmentTimeline(
        String logisticsCode,
        String carrierName,
        String resultStatus,
        String latestStatusText,
        String latestTrace,
        List<TrackNode> traces) {

    public ShipmentTimeline {
        traces = traces == null ? List.of() : List.copyOf(traces);
    }
}
