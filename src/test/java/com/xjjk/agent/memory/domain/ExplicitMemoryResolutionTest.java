package com.xjjk.agent.memory.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExplicitMemoryResolutionTest {

    private static final ExplicitMemoryCandidate CANDIDATE = new ExplicitMemoryCandidate(
            MemoryCategory.PROFILE_PREFERRED_NAME,
            "profile.preferred_name",
            "用户希望被称为石海文",
            "你以后都叫我石海文",
            MemoryRetentionType.NORMAL);

    @Test
    void createsSaveResolutionWithCandidatePathAndConfidence() {
        ExplicitMemoryResolution resolution = ExplicitMemoryResolution.save(
                CANDIDATE, ExplicitMemoryResolution.Path.SEMANTIC_PATH, 0.98);

        assertThat(resolution.action()).isEqualTo(ExplicitMemoryResolution.Action.SAVE);
        assertThat(resolution.candidate()).isEqualTo(CANDIDATE);
        assertThat(resolution.path()).isEqualTo(ExplicitMemoryResolution.Path.SEMANTIC_PATH);
        assertThat(resolution.confidence()).isEqualTo(0.98);
    }

    @Test
    void requiresCandidateAndARealSavePath() {
        assertThatThrownBy(() -> ExplicitMemoryResolution.save(
                null, ExplicitMemoryResolution.Path.SEMANTIC_PATH, 0.9))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ExplicitMemoryResolution.save(
                CANDIDATE, ExplicitMemoryResolution.Path.NONE, 0.9))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidSaveConfidence() {
        for (double value : new double[]{0.0, -0.1, Double.NaN, Double.POSITIVE_INFINITY, 1.01}) {
            assertThatThrownBy(() -> ExplicitMemoryResolution.save(
                    CANDIDATE, ExplicitMemoryResolution.Path.SEMANTIC_PATH, value))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void createsCandidateFreeClarifyAndNoneResolutions() {
        ExplicitMemoryResolution clarify = ExplicitMemoryResolution.clarify(
                ExplicitMemoryResolution.Path.SEMANTIC_PATH);
        ExplicitMemoryResolution none = ExplicitMemoryResolution.none();

        assertThat(clarify.action()).isEqualTo(ExplicitMemoryResolution.Action.CLARIFY);
        assertThat(clarify.candidate()).isNull();
        assertThat(clarify.path()).isEqualTo(ExplicitMemoryResolution.Path.SEMANTIC_PATH);
        assertThat(none.action()).isEqualTo(ExplicitMemoryResolution.Action.NONE);
        assertThat(none.candidate()).isNull();
        assertThat(none.path()).isEqualTo(ExplicitMemoryResolution.Path.NONE);
    }

    @Test
    void rejectsInvalidDirectRecordStates() {
        assertThatThrownBy(() -> new ExplicitMemoryResolution(
                ExplicitMemoryResolution.Action.CLARIFY,
                CANDIDATE,
                ExplicitMemoryResolution.Path.SEMANTIC_PATH,
                0.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExplicitMemoryResolution(
                ExplicitMemoryResolution.Action.NONE,
                null,
                ExplicitMemoryResolution.Path.SEMANTIC_PATH,
                0.0)).isInstanceOf(IllegalArgumentException.class);
    }
}
