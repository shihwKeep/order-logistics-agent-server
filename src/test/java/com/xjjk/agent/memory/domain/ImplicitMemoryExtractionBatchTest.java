package com.xjjk.agent.memory.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImplicitMemoryExtractionBatchTest {

    @Test
    void classifiesEmptyRejectedSavedAndUnchangedOutcomes() {
        assertThat(ImplicitMemoryExtractionBatch.observed(0, List.of())
                .resultCodeFor(0)).isEqualTo(MemoryExtractionResultCode.MODEL_EMPTY);
        assertThat(ImplicitMemoryExtractionBatch.observed(2, List.of())
                .resultCodeFor(0)).isEqualTo(MemoryExtractionResultCode.ALL_REJECTED);
        assertThat(ImplicitMemoryExtractionBatch.observed(2, List.of(candidate()))
                .resultCodeFor(1)).isEqualTo(MemoryExtractionResultCode.SAVED);
        assertThat(ImplicitMemoryExtractionBatch.observed(2, List.of(candidate()))
                .resultCodeFor(0)).isEqualTo(MemoryExtractionResultCode.NO_CHANGE);
        assertThat(ImplicitMemoryExtractionBatch.protocolRejected()
                .resultCodeFor(0))
                .isEqualTo(MemoryExtractionResultCode.MODEL_PROTOCOL_REJECTED);
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

    private static ImplicitMemoryCandidate candidate() {
        return new ImplicitMemoryCandidate(
                MemoryCategory.WORK_COMMON_SCOPE,
                "work.common_scope",
                "用户常用工作范围是Java开发",
                "Java开发",
                0.91);
    }
}
