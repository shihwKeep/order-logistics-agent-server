package com.xjjk.agent.order.domain;

/** 服务端确定性计算出的物流停滞判断，不由模型自行推算。 */
public record LogisticsStagnationAssessment(
        Status status,
        String stage,
        int thresholdHours,
        String latestTraceTime,
        String evaluatedAt,
        long elapsedHours,
        String reason) {

    public enum Status {
        EXCEEDED,
        WITHIN_THRESHOLD,
        UNKNOWN
    }
}
