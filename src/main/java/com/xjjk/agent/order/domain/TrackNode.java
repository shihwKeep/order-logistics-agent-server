package com.xjjk.agent.order.domain;

/** 运单的一条原始轨迹节点，顺序由物流服务确定。 */
public record TrackNode(String time, String location, String description) {
}
