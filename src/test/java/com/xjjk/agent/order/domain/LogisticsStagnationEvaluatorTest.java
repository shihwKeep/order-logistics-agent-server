package com.xjjk.agent.order.domain;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LogisticsStagnationEvaluatorTest {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Clock NOW = Clock.fixed(
            Instant.parse("2026-10-03T04:00:00Z"), BUSINESS_ZONE);

    private final LogisticsStagnationEvaluator evaluator =
            new LogisticsStagnationEvaluator(NOW);

    @Test
    void marksLinehaulShipmentExceededWhenLatestTraceIsOlderThanTwentyFourHours() {
        ShipmentTimeline shipment = shipment("在途", "2026-08-20 15:58:06");

        LogisticsStagnationAssessment assessment = evaluator.evaluate(shipment);

        assertThat(assessment.status())
                .isEqualTo(LogisticsStagnationAssessment.Status.EXCEEDED);
        assertThat(assessment.stage()).isEqualTo("干线");
        assertThat(assessment.thresholdHours()).isEqualTo(24);
        assertThat(assessment.elapsedHours()).isGreaterThan(24L);
    }

    @Test
    void keepsLinehaulShipmentWithinThresholdWhenLatestTraceIsRecent() {
        ShipmentTimeline shipment = shipment("在途", "2026-10-03 01:00:00");

        LogisticsStagnationAssessment assessment = evaluator.evaluate(shipment);

        assertThat(assessment.status())
                .isEqualTo(LogisticsStagnationAssessment.Status.WITHIN_THRESHOLD);
        assertThat(assessment.elapsedHours()).isEqualTo(11L);
    }

    @Test
    void returnsUnknownWhenLatestTraceTimeIsMissing() {
        ShipmentTimeline shipment = shipment("在途", "");

        LogisticsStagnationAssessment assessment = evaluator.evaluate(shipment);

        assertThat(assessment.status())
                .isEqualTo(LogisticsStagnationAssessment.Status.UNKNOWN);
        assertThat(assessment.reason()).contains("时间");
    }

    @Test
    void returnsUnknownWhenLogisticsStageCannotBeIdentified() {
        ShipmentTimeline shipment = shipment("异常状态", "2026-08-20 15:58:06");

        LogisticsStagnationAssessment assessment = evaluator.evaluate(shipment);

        assertThat(assessment.status())
                .isEqualTo(LogisticsStagnationAssessment.Status.UNKNOWN);
        assertThat(assessment.reason()).contains("环节");
    }

    @Test
    void fallsBackToOrderStatusWhenShipmentStatusIsOnlyUpdateMarker() {
        ShipmentTimeline shipment = shipment("已更新", "2026-08-20 15:58:06");

        LogisticsStagnationAssessment assessment = evaluator.evaluate(shipment, "在途");

        assertThat(assessment.status())
                .isEqualTo(LogisticsStagnationAssessment.Status.EXCEEDED);
        assertThat(assessment.stage()).isEqualTo("干线");
        assertThat(assessment.thresholdHours()).isEqualTo(24);
    }

    private ShipmentTimeline shipment(String status, String time) {
        return new ShipmentTimeline(
                "DPK365068298955", "德邦", "SUCCESS", status,
                "到达转运节点", List.of(new TrackNode(time, "南京", "到达转运节点")));
    }
}
