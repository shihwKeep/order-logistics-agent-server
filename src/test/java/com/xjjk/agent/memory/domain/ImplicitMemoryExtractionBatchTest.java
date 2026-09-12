package com.xjjk.agent.memory.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImplicitMemoryExtractionBatchTest {

    @Test
    void classifiesSemanticDecisionsRejectionsSavedAndUnchangedOutcomes() {
        assertThat(ImplicitMemoryExtractionBatch.decision(MemoryDecision.IGNORE)
                .resultCodeFor(0)).isEqualTo(MemoryExtractionResultCode.IGNORE);
        assertThat(ImplicitMemoryExtractionBatch.decision(MemoryDecision.SESSION_ONLY)
                .resultCodeFor(0)).isEqualTo(MemoryExtractionResultCode.SESSION_ONLY);
        assertThat(ImplicitMemoryExtractionBatch.rejected(2,
                MemoryExtractionResultCode.REJECTED_EVIDENCE)
                .resultCodeFor(0)).isEqualTo(MemoryExtractionResultCode.REJECTED_EVIDENCE);
        assertThat(ImplicitMemoryExtractionBatch.observed(2, List.of(candidate()))
                .resultCodeFor(1)).isEqualTo(MemoryExtractionResultCode.SAVED);
        assertThat(ImplicitMemoryExtractionBatch.observed(2, List.of(candidate()))
                .resultCodeFor(0)).isEqualTo(MemoryExtractionResultCode.NO_CHANGE);
        assertThat(ImplicitMemoryExtractionBatch.protocolRejected().resultCodeFor(0))
                .isEqualTo(MemoryExtractionResultCode.MODEL_PROTOCOL_REJECTED);
    }

    @Test
    void supportsVerifierRejectionCodes() {
        assertThat(ImplicitMemoryExtractionBatch.rejected(
                1, MemoryExtractionResultCode.REJECTED_CONTRADICTED).resultCodeFor(0))
                .isEqualTo(MemoryExtractionResultCode.REJECTED_CONTRADICTED);
        assertThat(ImplicitMemoryExtractionBatch.rejected(
                1, MemoryExtractionResultCode.REJECTED_UNCERTAIN).resultCodeFor(0))
                .isEqualTo(MemoryExtractionResultCode.REJECTED_UNCERTAIN);
    }

    @Test
    void rejectsImpossibleCountsAndInvalidEmptyResultState() {
        assertThatThrownBy(() -> ImplicitMemoryExtractionBatch.observed(
                0, List.of(candidate())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ImplicitMemoryExtractionBatch.observed(
                1, List.of(candidate())).resultCodeFor(2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ImplicitMemoryExtractionBatch(
                0, List.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ImplicitMemoryExtractionBatch(
                1, List.of(candidate()), MemoryExtractionResultCode.MODEL_EMPTY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ValidatedMemoryFact candidate() {
        MemoryFactCandidate candidate = new MemoryFactCandidate(
                MemoryType.WORK_CONTEXT, "primary_programming_language", "Java", "Java",
                "我平时用 Java 开发", MemoryStability.STABLE, 0.91);
        return new ValidatedMemoryFact(candidate, "work.primary_programming_language",
                "用户主要使用 Java 进行开发", "\"Java\"", "WORK_COMMON_SCOPE",
                "DETERMINISTIC");
    }
}
