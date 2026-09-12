package com.xjjk.agent.memory.recall;

import com.xjjk.agent.memory.persistence.entity.UserMemoryEntity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryCandidateSelectorTest {

    @Test
    void appliesSuppressionExplicitPriorityKeyAndTextDedupeAndTopK() {
        MemoryCandidateSelector selector = new MemoryCandidateSelector();
        UserMemoryEntity explicit = memory(
                "explicit", "USER_EXPLICIT", "PREFERENCE_LANGUAGE",
                "preference.language", "用户偏好使用中文回答", 1.0, 5);
        UserMemoryEntity duplicateImplicit = memory(
                "implicit-duplicate", "AUTO_EXTRACT", "PREFERENCE_LANGUAGE",
                "preference.language", "用户偏好使用中文回答。", 0.99, 6);
        UserMemoryEntity suppressed = memory(
                "suppressed", "AUTO_EXTRACT", "WORK_COMMON_SCOPE",
                "work.common_scope.deleted", "用户主要从事 Python 开发", 0.98, 7);
        UserMemoryEntity work = memory(
                "work", "AUTO_EXTRACT", "WORK_COMMON_SCOPE",
                "work.common_scope.java", "用户主要从事 Java 开发", 0.95, 8);
        List<MemorySelectionCandidate> semantic = List.of(
                new MemorySelectionCandidate(duplicateImplicit, 0.99, 1, false),
                new MemorySelectionCandidate(suppressed, 0.98, 2, false),
                new MemorySelectionCandidate(work, 0.90, 3, false));

        List<RecalledMemory> selected = selector.select(
                List.of(explicit), semantic,
                Set.of("work.common_scope.deleted"), 2);

        assertThat(selected).extracting(RecalledMemory::memoryId)
                .containsExactly("explicit", "work");
    }

    @Test
    void dropsAmbiguousImplicitSingletonConflicts() {
        MemoryCandidateSelector selector = new MemoryCandidateSelector();
        UserMemoryEntity chinese = memory(
                "zh", "AUTO_EXTRACT", "PREFERENCE_LANGUAGE",
                "preference.language.zh", "用户偏好中文回答", 0.91, 5);
        UserMemoryEntity english = memory(
                "en", "AUTO_EXTRACT", "PREFERENCE_LANGUAGE",
                "preference.language.en", "用户偏好英文回答", 0.90, 6);

        List<RecalledMemory> selected = selector.select(List.of(), List.of(
                new MemorySelectionCandidate(chinese, 0.90, 1, false),
                new MemorySelectionCandidate(english, 0.89, 2, false)), Set.of(), 5);

        assertThat(selected).isEmpty();
    }

    private UserMemoryEntity memory(
            String id, String source, String category, String key,
            String content, double confidence, int minute) {
        UserMemoryEntity value = new UserMemoryEntity();
        value.setMemoryId(id);
        value.setTenantId(7L);
        value.setUserId(9L);
        value.setMemoryGeneration(3L);
        value.setVersion(1L);
        value.setSourceType(source);
        value.setCategory(category);
        value.setCanonicalKey(key);
        value.setContent(content);
        value.setConfidence(BigDecimal.valueOf(confidence));
        value.setStatus("ACTIVE");
        value.setExpiresAt(LocalDateTime.parse("2027-09-12T08:00:00"));
        value.setUpdatedAt(LocalDateTime.parse("2026-09-12T08:%02d:00".formatted(minute)));
        return value;
    }
}
