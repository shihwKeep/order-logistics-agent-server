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
            return valueClauseSupports(candidate, evidenceText);
        }
        return scopeMatches(candidate.temporalScope(), historical, current);
    }

    private static boolean valueClauseSupports(
            MemoryFactCandidate candidate,
            String evidenceText) {
        String valueEvidence = candidate.valueEvidence();
        if (valueEvidence == null || valueEvidence.isBlank()) {
            return false;
        }
        int fromIndex = 0;
        while (fromIndex < evidenceText.length()) {
            int valueIndex = MemoryEvidenceTextMatcher.indexOf(
                    evidenceText, valueEvidence, fromIndex);
            if (valueIndex < 0) {
                return false;
            }
            String clause = containingClause(evidenceText, valueIndex,
                    valueIndex + valueEvidence.length());
            boolean historical = containsAny(clause, HISTORICAL_ANCHORS);
            boolean current = containsAny(clause, CURRENT_ANCHORS);
            if (scopeMatches(candidate.temporalScope(), historical, current)) {
                return true;
            }
            fromIndex = valueIndex + valueEvidence.length();
        }
        return false;
    }

    private static boolean scopeMatches(
            MemoryTemporalScope scope,
            boolean historical,
            boolean current) {
        if (historical && current) {
            return false;
        }
        return switch (scope) {
            case HISTORICAL -> historical;
            case CURRENT -> !historical;
        };
    }

    private static String containingClause(
            String text,
            int valueStart,
            int valueEnd) {
        int start = valueStart;
        while (start > 0 && !isClauseBoundary(text.charAt(start - 1))) {
            start--;
        }
        int end = valueEnd;
        while (end < text.length() && !isClauseBoundary(text.charAt(end))) {
            end++;
        }
        return text.substring(start, end);
    }

    private static boolean isClauseBoundary(char value) {
        return value == '，' || value == ',' || value == '。' || value == '.'
                || value == '！' || value == '!' || value == '？' || value == '?'
                || value == '；' || value == ';' || value == '\n' || value == '\r';
    }

    private static boolean containsAny(String value, List<String> anchors) {
        return anchors.stream().anyMatch(value::contains);
    }
}
