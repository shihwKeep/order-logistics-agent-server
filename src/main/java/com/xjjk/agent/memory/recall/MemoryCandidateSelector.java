package com.xjjk.agent.memory.recall;

import com.xjjk.agent.memory.persistence.entity.UserMemoryEntity;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 显式优先、确定性冲突消解及文本去重。 */
@Component
public class MemoryCandidateSelector {
    private static final Set<String> SINGLETON_CATEGORIES = Set.of(
            "PROFILE_PREFERRED_NAME", "PREFERENCE_LANGUAGE", "PREFERENCE_ANSWER_STYLE");
    private static final double TEXT_DUPLICATE_THRESHOLD = 0.88D;

    public List<RecalledMemory> selectAuthoritative(
            List<UserMemoryEntity> authoritative,
            Set<String> suppressedKeys,
            int maxSelected) {
        return select(authoritative, List.of(), suppressedKeys, maxSelected);
    }

    public List<RecalledMemory> select(
            List<UserMemoryEntity> globalExplicit,
            List<MemorySelectionCandidate> semantic,
            Set<String> suppressedKeys,
            int maxSelected) {
        if (maxSelected <= 0 || maxSelected > 10) {
            throw new IllegalArgumentException("记忆最终数量不合法");
        }
        Set<String> suppressions = Set.copyOf(suppressedKeys);
        List<MemorySelectionCandidate> all = new ArrayList<>();
        for (UserMemoryEntity memory : globalExplicit) {
            all.add(new MemorySelectionCandidate(memory, Double.MAX_VALUE, 0, true));
        }
        all.addAll(semantic);
        all.removeIf(candidate -> suppressions.contains(candidate.memory().getCanonicalKey()));

        Set<String> unsafeCategories = unsafeImplicitSingletonCategories(all);
        all.removeIf(candidate -> unsafeCategories.contains(candidate.memory().getCategory()));
        Set<String> explicitSingletonCategories = all.stream()
                .map(MemorySelectionCandidate::memory)
                .filter(this::explicit)
                .map(UserMemoryEntity::getCategory)
                .filter(SINGLETON_CATEGORIES::contains)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        all.removeIf(candidate -> !explicit(candidate.memory())
                && explicitSingletonCategories.contains(candidate.memory().getCategory()));

        Map<String, MemorySelectionCandidate> byKey = new LinkedHashMap<>();
        for (MemorySelectionCandidate candidate : all) {
            byKey.merge(candidate.memory().getCanonicalKey(), candidate, this::sameKeyWinner);
        }
        List<MemorySelectionCandidate> ordered = new ArrayList<>(byKey.values());
        ordered.sort(ordering());

        List<MemorySelectionCandidate> deduplicated = new ArrayList<>();
        for (MemorySelectionCandidate candidate : ordered) {
            boolean duplicate = deduplicated.stream().anyMatch(existing ->
                    textSimilarity(existing.memory().getContent(),
                            candidate.memory().getContent()) >= TEXT_DUPLICATE_THRESHOLD);
            if (!duplicate) {
                deduplicated.add(candidate);
            }
        }
        return deduplicated.stream().limit(maxSelected)
                .map(candidate -> toRecalled(candidate.memory())).toList();
    }

    private Set<String> unsafeImplicitSingletonCategories(
            List<MemorySelectionCandidate> candidates) {
        Map<String, Set<String>> implicitKeys = new HashMap<>();
        Set<String> explicitCategories = new HashSet<>();
        for (MemorySelectionCandidate candidate : candidates) {
            UserMemoryEntity memory = candidate.memory();
            if (!SINGLETON_CATEGORIES.contains(memory.getCategory())) {
                continue;
            }
            if ("USER_EXPLICIT".equals(memory.getSourceType())) {
                explicitCategories.add(memory.getCategory());
            } else {
                implicitKeys.computeIfAbsent(memory.getCategory(), ignored -> new HashSet<>())
                        .add(memory.getCanonicalKey());
            }
        }
        Set<String> unsafe = new HashSet<>();
        implicitKeys.forEach((category, keys) -> {
            if (keys.size() > 1 && !explicitCategories.contains(category)) {
                unsafe.add(category);
            }
        });
        return unsafe;
    }

    private MemorySelectionCandidate sameKeyWinner(
            MemorySelectionCandidate left, MemorySelectionCandidate right) {
        boolean leftExplicit = explicit(left.memory());
        boolean rightExplicit = explicit(right.memory());
        if (leftExplicit != rightExplicit) {
            return leftExplicit ? left : right;
        }
        return ordering().compare(left, right) <= 0 ? left : right;
    }

    private Comparator<MemorySelectionCandidate> ordering() {
        return Comparator
                .comparing(MemorySelectionCandidate::directGlobal).reversed()
                .thenComparing(MemorySelectionCandidate::relevanceScore,
                        Comparator.reverseOrder())
                .thenComparing(candidate -> explicit(candidate.memory()),
                        Comparator.reverseOrder())
                .thenComparing(candidate -> confidence(candidate.memory()),
                        Comparator.reverseOrder())
                .thenComparing(candidate -> updatedAt(candidate.memory()),
                        Comparator.reverseOrder())
                .thenComparing(candidate -> candidate.memory().getMemoryId());
    }

    private boolean explicit(UserMemoryEntity memory) {
        return "USER_EXPLICIT".equals(memory.getSourceType());
    }

    private BigDecimal confidence(UserMemoryEntity memory) {
        return memory.getConfidence() == null ? BigDecimal.ZERO : memory.getConfidence();
    }

    private LocalDateTime updatedAt(UserMemoryEntity memory) {
        return memory.getUpdatedAt() == null ? LocalDateTime.MIN : memory.getUpdatedAt();
    }

    private RecalledMemory toRecalled(UserMemoryEntity memory) {
        return new RecalledMemory(
                memory.getMemoryId(), memory.getVersion(), memory.getSourceType(),
                memory.getCategory(), memory.getCanonicalKey(), memory.getContent(),
                confidence(memory), updatedAt(memory));
    }

    private double textSimilarity(String left, String right) {
        String a = normalize(left);
        String b = normalize(right);
        if (a.equals(b)) {
            return 1D;
        }
        Set<String> aGrams = bigrams(a);
        Set<String> bGrams = bigrams(b);
        if (aGrams.isEmpty() || bGrams.isEmpty()) {
            return 0D;
        }
        Set<String> intersection = new HashSet<>(aGrams);
        intersection.retainAll(bGrams);
        Set<String> union = new HashSet<>(aGrams);
        union.addAll(bGrams);
        return (double) intersection.size() / union.size();
    }

    private String normalize(String value) {
        if (value == null) return "";
        StringBuilder normalized = new StringBuilder();
        value.codePoints().filter(Character::isLetterOrDigit)
                .forEach(normalized::appendCodePoint);
        return normalized.toString().toLowerCase(java.util.Locale.ROOT);
    }

    private Set<String> bigrams(String value) {
        int[] points = value.codePoints().toArray();
        Set<String> result = new HashSet<>();
        if (points.length == 1) {
            result.add(value);
        }
        for (int index = 0; index + 1 < points.length; index++) {
            result.add(new String(points, index, 2));
        }
        return result;
    }
}
