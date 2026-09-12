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
