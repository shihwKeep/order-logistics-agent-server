package com.xjjk.agent.memory.service;

import com.xjjk.agent.memory.domain.MemoryFactCandidate;
import com.xjjk.agent.memory.domain.MemoryTemporalScope;

import java.util.List;

/** 对模型提供的时态标签执行保守、确定性的原文锚点校验。 */
public final class MemoryTemporalEvidencePolicy {

    private static final List<String> HISTORICAL_ANCHORS = List.of(
            "以前", "曾经", "过去", "原来", "之前", "曾任");
    private static final List<String> CURRENT_ANCHORS = List.of(
            "现在", "目前", "如今", "当前", "今年", "现任", "现为");

    public boolean isSupported(MemoryFactCandidate candidate, String evidenceText) {
        if (candidate == null || candidate.temporalScope() == null
                || evidenceText == null || evidenceText.isBlank()) {
            return false;
        }
        boolean historical = containsAny(evidenceText, HISTORICAL_ANCHORS);
        boolean current = containsAny(evidenceText, CURRENT_ANCHORS);
        if (historical && current) {
            return false;
        }
        return switch (candidate.temporalScope()) {
            case HISTORICAL -> historical;
            case CURRENT -> !historical;
        };
    }

    private static boolean containsAny(String value, List<String> anchors) {
        return anchors.stream().anyMatch(value::contains);
    }
}
