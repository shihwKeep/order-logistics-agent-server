package com.xjjk.agent.memory.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemorySemanticDomainTest {

    @Test
    void representsIgnoreSessionAndLongTermDecisions() {
        MemoryFactCandidate java = candidate(MemoryStability.STABLE, 0.96);

        assertThat(MemoryExtractionDecision.ignore().decision())
                .isEqualTo(MemoryDecision.IGNORE);
        assertThat(MemoryExtractionDecision.sessionOnly().decision())
                .isEqualTo(MemoryDecision.SESSION_ONLY);
        assertThat(MemoryExtractionDecision.longTerm(
                MemoryExplicitness.IMPLICIT, List.of(java)).candidates())
                .containsExactly(java);
    }

    @Test
    void rejectsInvalidDecisionCandidateCombinations() {
        MemoryFactCandidate temporary = candidate(MemoryStability.TEMPORARY, 0.96);

        assertThatThrownBy(() -> MemoryExtractionDecision.longTerm(
                MemoryExplicitness.IMPLICIT, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MemoryExtractionDecision.longTerm(
                MemoryExplicitness.IMPLICIT, List.of(temporary)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MemoryExtractionDecision(
                MemoryDecision.IGNORE, MemoryExplicitness.IMPLICIT,
                List.of(candidate(MemoryStability.STABLE, 0.96))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidAtomicFactFields() {
        assertThatThrownBy(() -> new MemoryFactCandidate(
                MemoryType.WORK_CONTEXT, " ", "Java", "Java",
                "我使用 Java", MemoryStability.STABLE, 0.96))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> candidate(MemoryStability.STABLE, Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> candidate(MemoryStability.STABLE, 1.01))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void supportsTimeBoundFactsWithCurrentScopeByDefault() {
        MemoryFactCandidate candidate = candidate(MemoryStability.TIME_BOUND, 0.96);

        assertThat(MemoryTemporalScope.values())
                .containsExactly(MemoryTemporalScope.CURRENT, MemoryTemporalScope.HISTORICAL);
        assertThat(candidate.stability()).isEqualTo(MemoryStability.TIME_BOUND);
        assertThat(candidate.temporalScope()).isEqualTo(MemoryTemporalScope.CURRENT);
    }

    @Test
    void carriesExplicitHistoricalScopeThroughCandidateAndValidatedFact() {
        MemoryFactCandidate candidate = new MemoryFactCandidate(
                MemoryType.WORK_CONTEXT,
                "primary_programming_language",
                "Java",
                "Java",
                "我以前用 Java 语言进行开发",
                MemoryStability.TIME_BOUND,
                MemoryTemporalScope.HISTORICAL,
                0.96);
        ValidatedMemoryFact fact = new ValidatedMemoryFact(
                candidate,
                "work.primary_programming_language",
                "用户以前主要使用 Java 进行开发",
                "\"Java\"",
                "WORK_COMMON_SCOPE",
                "DETERMINISTIC",
                MemoryTemporalScope.HISTORICAL);

        assertThat(candidate.temporalScope()).isEqualTo(MemoryTemporalScope.HISTORICAL);
        assertThat(fact.temporalScope()).isEqualTo(MemoryTemporalScope.HISTORICAL);
    }

    @Test
    void rejectsNullTemporalScope() {
        assertThatThrownBy(() -> new MemoryFactCandidate(
                MemoryType.WORK_CONTEXT,
                "primary_programming_language",
                "Java",
                "Java",
                "我平时用 Java 语言进行开发",
                MemoryStability.STABLE,
                null,
                0.96))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void validatedFactCarriesOnlyServerResolvedPersistenceFields() {
        MemoryFactCandidate candidate = candidate(MemoryStability.STABLE, 0.96);
        ValidatedMemoryFact fact = new ValidatedMemoryFact(
                candidate,
                "work.primary_programming_language",
                "用户主要使用 Java 进行开发",
                "\"Java\"",
                "WORK_COMMON_SCOPE",
                "DETERMINISTIC");

        assertThat(fact.canonicalKey()).isEqualTo("work.primary_programming_language");
        assertThat(fact.canonicalContent()).isEqualTo("用户主要使用 Java 进行开发");
        assertThat(fact.confidence()).isEqualTo(0.96);
        assertThat(fact.temporalScope()).isEqualTo(MemoryTemporalScope.CURRENT);
    }

    private static MemoryFactCandidate candidate(
            MemoryStability stability,
            double confidence) {
        return new MemoryFactCandidate(
                MemoryType.WORK_CONTEXT,
                "primary_programming_language",
                "Java",
                "Java",
                "我平时用 Java 语言进行开发",
                stability,
                confidence);
    }
}
