package com.xjjk.agent.memory.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserMemoryDomainTest {

    @Test
    void exposesOnlyApprovedInitialCategories() {
        assertThat(MemoryCategory.values()).containsExactly(
                MemoryCategory.PROFILE_PREFERRED_NAME,
                MemoryCategory.PROFILE_PERSONAL_FACT,
                MemoryCategory.PREFERENCE_LANGUAGE,
                MemoryCategory.PREFERENCE_ANSWER_STYLE,
                MemoryCategory.WORK_COMMON_SCOPE
        );
        assertThat(MemoryCategory.PROFILE_PREFERRED_NAME.keyPrefix())
                .isEqualTo("profile.preferred_name");
        assertThat(MemoryCategory.PROFILE_PERSONAL_FACT.keyPrefix())
                .isEqualTo("profile.personal");
    }

    @Test
    void keepsPersistenceStatesExplicit() {
        assertThat(MemorySourceType.values())
                .containsExactly(MemorySourceType.USER_EXPLICIT,
                        MemorySourceType.AUTO_EXTRACT);
        assertThat(MemoryRetentionType.values())
                .containsExactly(MemoryRetentionType.NORMAL,
                        MemoryRetentionType.PERMANENT);
        assertThat(MemoryStatus.values())
                .containsExactly(MemoryStatus.ACTIVE,
                        MemoryStatus.SUPERSEDED,
                        MemoryStatus.DELETED,
                        MemoryStatus.EXPIRED);
        assertThat(MemoryVisibility.values())
                .containsExactly(MemoryVisibility.VISIBLE,
                        MemoryVisibility.HIDDEN);
        assertThat(MemoryOutboxStatus.values())
                .containsExactly(MemoryOutboxStatus.PENDING,
                        MemoryOutboxStatus.PROCESSING,
                        MemoryOutboxStatus.RETRY,
                        MemoryOutboxStatus.DONE,
                        MemoryOutboxStatus.DEAD);
        assertThat(MemorySuppressionStatus.values())
                .containsExactly(MemorySuppressionStatus.ACTIVE,
                        MemorySuppressionStatus.LIFTED);
    }

    @Test
    void notHandledCommandCarriesNoSuccessPayload() {
        ExplicitMemoryCommandResult result =
                ExplicitMemoryCommandResult.notHandled();

        assertThat(result.handled()).isFalse();
        assertThat(result.saved()).isFalse();
        assertThat(result.assistantText()).isNull();
        assertThat(result.memoryId()).isNull();
    }
}
