package com.xjjk.agent.order.domain;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

/** 根据物流状态和最新轨迹时间计算停滞阈值。 */
public final class LogisticsStagnationEvaluator {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter LOCAL_TIME =
            DateTimeFormatter.ofPattern("yyyy-M-d H:mm:ss");

    private final Clock clock;

    public LogisticsStagnationEvaluator() {
        this(Clock.system(BUSINESS_ZONE));
    }

    public LogisticsStagnationEvaluator(Clock clock) {
        this.clock = clock.withZone(BUSINESS_ZONE);
    }

    public LogisticsStagnationAssessment evaluate(ShipmentTimeline shipment) {
        Stage stage = Stage.from(shipment == null ? null : shipment.latestStatusText());
        String latestTraceTime = latestTraceTime(shipment == null ? List.of() : shipment.traces());
        String evaluatedAt = ZonedDateTime.now(clock).toOffsetDateTime().toString();
        if (stage == Stage.UNKNOWN) {
            return new LogisticsStagnationAssessment(
                    LogisticsStagnationAssessment.Status.UNKNOWN,
                    stage.label,
                    stage.thresholdHours,
                    latestTraceTime == null ? "未提供" : latestTraceTime,
                    evaluatedAt,
                    0,
                    "物流环节无法识别，不能匹配停滞阈值");
        }
        if (latestTraceTime == null) {
            return new LogisticsStagnationAssessment(
                    LogisticsStagnationAssessment.Status.UNKNOWN,
                    stage.label,
                    stage.thresholdHours,
                    "未提供",
                    evaluatedAt,
                    0,
                    "缺少最新轨迹时间，无法计算停滞时长");
        }

        try {
            ZonedDateTime latest = parse(latestTraceTime);
            ZonedDateTime now = ZonedDateTime.now(clock);
            long elapsedHours = Math.max(0, Duration.between(latest, now).toHours());
            LogisticsStagnationAssessment.Status status = elapsedHours >= stage.thresholdHours
                    ? LogisticsStagnationAssessment.Status.EXCEEDED
                    : LogisticsStagnationAssessment.Status.WITHIN_THRESHOLD;
            String reason = status == LogisticsStagnationAssessment.Status.EXCEEDED
                    ? "已达到当前物流环节的停滞阈值"
                    : "尚未达到当前物流环节的停滞阈值";
            return new LogisticsStagnationAssessment(
                    status, stage.label, stage.thresholdHours,
                    latestTraceTime, evaluatedAt, elapsedHours, reason);
        } catch (DateTimeParseException exception) {
            return new LogisticsStagnationAssessment(
                    LogisticsStagnationAssessment.Status.UNKNOWN,
                    stage.label,
                    stage.thresholdHours,
                    latestTraceTime,
                    evaluatedAt,
                    0,
                    "最新轨迹时间格式无法解析，无法计算停滞时长");
        }
    }

    private ZonedDateTime parse(String value) {
        try {
            return OffsetDateTime.parse(value).atZoneSameInstant(BUSINESS_ZONE);
        } catch (DateTimeParseException ignored) {
            return LocalDateTime.parse(value, LOCAL_TIME).atZone(BUSINESS_ZONE);
        }
    }

    private String latestTraceTime(List<TrackNode> traces) {
        return traces.stream()
                .map(TrackNode::time)
                .filter(time -> time != null && !time.isBlank())
                .findFirst()
                .orElse(null);
    }

    private enum Stage {
        UNKNOWN("未知", 0),
        UNCOLLECTED("未揽收", 6),
        DELIVERY("派送", 12),
        COLD_CHAIN("冷链", 2),
        LINEHAUL("干线", 24);

        private final String label;
        private final int thresholdHours;

        Stage(String label, int thresholdHours) {
            this.label = label;
            this.thresholdHours = thresholdHours;
        }

        private static Stage from(String status) {
            String normalized = status == null ? "" : status.toLowerCase(Locale.ROOT);
            if (normalized.contains("冷链") || normalized.contains("温控")) return COLD_CHAIN;
            if (normalized.contains("派送") || normalized.contains("配送")
                    || normalized.contains("delivering")) return DELIVERY;
            if (normalized.contains("未揽收") || normalized.contains("待揽收")
                    || normalized.contains("not picked") || normalized.contains("not_picked")) {
                return UNCOLLECTED;
            }
            if (normalized.contains("在途") || normalized.contains("运输")
                    || normalized.contains("干线") || normalized.contains("in_transit")
                    || normalized.contains("in transit")) return LINEHAUL;
            return UNKNOWN;
        }
    }
}
