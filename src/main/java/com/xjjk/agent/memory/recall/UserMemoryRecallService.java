package com.xjjk.agent.memory.recall;

import com.xjjk.agent.identity.domain.AgentIdentity;
import com.xjjk.agent.memory.config.MemoryRetrievalProperties;
import com.xjjk.agent.memory.config.UserMemoryProperties;
import com.xjjk.agent.memory.domain.MemoryCategory;
import com.xjjk.agent.memory.domain.MemoryTemporalScope;
import com.xjjk.agent.memory.persistence.entity.UserMemoryEntity;
import com.xjjk.agent.memory.persistence.entity.UserMemorySettingEntity;
import com.xjjk.agent.memory.persistence.mapper.MemorySuppressionMapper;
import com.xjjk.agent.memory.persistence.mapper.UserMemoryMapper;
import com.xjjk.agent.memory.persistence.mapper.UserMemorySettingMapper;
import com.xjjk.agent.memory.observation.UserMemoryMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 从索引取得候选后，以 MySQL 为唯一事实源执行终审。 */
@Slf4j
@Service
public class UserMemoryRecallService {
    private static final Set<String> SOURCES = Set.of("USER_EXPLICIT", "AUTO_EXTRACT");
    private static final Set<String> GLOBAL_CATEGORIES = Set.of(
            "PROFILE_PREFERRED_NAME", "PREFERENCE_LANGUAGE", "PREFERENCE_ANSWER_STYLE");

    private final UserMemorySettingMapper settingMapper;
    private final UserMemoryMapper memoryMapper;
    private final MemorySuppressionMapper suppressionMapper;
    private final MemoryRecallGateway gateway;
    private final MemoryRecallGate gate;
    private final MemoryCandidateSelector selector;
    private final UserMemoryProperties memoryProperties;
    private final MemoryRetrievalProperties retrievalProperties;
    private final Clock clock;
    private final UserMemoryMetrics metrics;

    @Autowired
    public UserMemoryRecallService(
            UserMemorySettingMapper settingMapper,
            UserMemoryMapper memoryMapper,
            MemorySuppressionMapper suppressionMapper,
            MemoryRecallGateway gateway,
            MemoryRecallGate gate,
            MemoryCandidateSelector selector,
            UserMemoryProperties memoryProperties,
            MemoryRetrievalProperties retrievalProperties,
            UserMemoryMetrics metrics) {
        this(settingMapper, memoryMapper, suppressionMapper, gateway, gate, selector,
                memoryProperties, retrievalProperties, Clock.systemUTC(), metrics);
    }

    UserMemoryRecallService(
            UserMemorySettingMapper settingMapper,
            UserMemoryMapper memoryMapper,
            MemorySuppressionMapper suppressionMapper,
            MemoryRecallGateway gateway,
            MemoryRecallGate gate,
            MemoryCandidateSelector selector,
            UserMemoryProperties memoryProperties,
            MemoryRetrievalProperties retrievalProperties,
            Clock clock) {
        this(settingMapper, memoryMapper, suppressionMapper, gateway, gate, selector,
                memoryProperties, retrievalProperties, clock, null);
    }

    UserMemoryRecallService(
            UserMemorySettingMapper settingMapper,
            UserMemoryMapper memoryMapper,
            MemorySuppressionMapper suppressionMapper,
            MemoryRecallGateway gateway,
            MemoryRecallGate gate,
            MemoryCandidateSelector selector,
            UserMemoryProperties memoryProperties,
            MemoryRetrievalProperties retrievalProperties,
            Clock clock,
            UserMemoryMetrics metrics) {
        this.settingMapper = Objects.requireNonNull(settingMapper, "settingMapper");
        this.memoryMapper = Objects.requireNonNull(memoryMapper, "memoryMapper");
        this.suppressionMapper = Objects.requireNonNull(suppressionMapper, "suppressionMapper");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.selector = Objects.requireNonNull(selector, "selector");
        this.memoryProperties = Objects.requireNonNull(memoryProperties, "memoryProperties");
        this.retrievalProperties = Objects.requireNonNull(
                retrievalProperties, "retrievalProperties");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.metrics = metrics;
    }

    public UserMemoryRecallResult recall(AgentIdentity identity, String query) {
        Objects.requireNonNull(identity, "identity");
        return recall(identity.tenantId(), identity.userId(), query);
    }

    public UserMemoryRecallResult recallByCategory(
            AgentIdentity identity,
            MemoryCategory category) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(category, "category");
        if (identity.tenantId() <= 0 || identity.userId() <= 0) {
            return UserMemoryRecallResult.invalidRequest();
        }
        if (!memoryProperties.enabled()) {
            return UserMemoryRecallResult.disabled();
        }
        try {
            return recallCategorySafely(
                    identity.tenantId(), identity.userId(), category);
        } catch (RuntimeException failure) {
            log.warn("user_memory_category_recall result=DEGRADED "
                            + "errorCode=MYSQL_VALIDATION_FAILED exceptionType={}",
                    failure.getClass().getSimpleName());
            return UserMemoryRecallResult.unavailable();
        }
    }

    public UserMemoryRecallResult recallByPredicates(
            AgentIdentity identity,
            List<String> predicateNames,
            MemoryCategory legacyCategory) {
        return recallByPredicates(
                identity, predicateNames, legacyCategory, MemoryTemporalScope.CURRENT);
    }

    public UserMemoryRecallResult recallByPredicates(
            AgentIdentity identity,
            List<String> predicateNames,
            MemoryCategory legacyCategory,
            MemoryTemporalScope temporalScope) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(predicateNames, "predicateNames");
        Objects.requireNonNull(legacyCategory, "legacyCategory");
        Objects.requireNonNull(temporalScope, "temporalScope");
        List<String> predicates = predicateNames.stream()
                .filter(Objects::nonNull)
                .map(String::strip)
                .filter(value -> value.matches("[a-z][a-z0-9_]{0,63}"))
                .distinct()
                .limit(8)
                .toList();
        if (identity.tenantId() <= 0 || identity.userId() <= 0 || predicates.isEmpty()) {
            return UserMemoryRecallResult.invalidRequest();
        }
        if (!memoryProperties.enabled()) {
            return UserMemoryRecallResult.disabled();
        }
        try {
            return recallPredicatesSafely(identity.tenantId(), identity.userId(),
                    predicates, legacyCategory, temporalScope);
        } catch (RuntimeException failure) {
            log.warn("user_memory_predicate_recall result=DEGRADED "
                            + "errorCode=MYSQL_VALIDATION_FAILED exceptionType={}",
                    failure.getClass().getSimpleName());
            return UserMemoryRecallResult.unavailable();
        }
    }

    public UserMemoryRecallResult recall(long tenantId, long userId, String query) {
        if (tenantId <= 0 || userId <= 0
                || query == null || query.isBlank() || query.length() > 2_000) {
            return UserMemoryRecallResult.invalidRequest();
        }
        if (!memoryProperties.enabled()) {
            return UserMemoryRecallResult.disabled();
        }
        try {
            return recallSafely(tenantId, userId, query.strip());
        } catch (RuntimeException failure) {
            // 记忆是可选增强；数据库终审异常时整批失效，不能阻断普通聊天。
            log.warn("user_memory_recall result=DEGRADED errorCode=MYSQL_VALIDATION_FAILED exceptionType={}",
                    failure.getClass().getSimpleName());
            return UserMemoryRecallResult.unavailable();
        }
    }

    private UserMemoryRecallResult recallSafely(long tenantId, long userId, String query) {
        UserMemorySettingEntity setting = settingMapper.selectOwned(
                tenantId, userId);
        if (setting == null) {
            return UserMemoryRecallResult.notInitialized();
        }
        if (!Boolean.TRUE.equals(setting.getMemoryEnabled())) {
            return UserMemoryRecallResult.disabled();
        }
        if (setting.getMemoryGeneration() == null
                || setting.getMemoryGeneration() <= 0) {
            return UserMemoryRecallResult.unavailable();
        }
        long generation = setting.getMemoryGeneration();
        LocalDateTime now = now();
        List<UserMemoryEntity> rawGlobals = memoryMapper.selectGlobalExplicit(
                tenantId, userId, generation, now,
                retrievalProperties.globalExplicitLimit());
        List<UserMemoryEntity> globals = rawGlobals.stream()
                .filter(memory -> validGlobal(memory, tenantId, userId, generation, now))
                .toList();
        rejected("OWNER_OR_STATE", rawGlobals.size() - globals.size());

        boolean semanticAttempted = gate.shouldRetrieve(query)
                || gate.allowsSemanticFallback(query);
        String semanticCode = semanticAttempted ? "NO_CANDIDATE" : "SKIPPED";
        List<MemorySelectionCandidate> semantic = List.of();
        if (semanticAttempted) {
            MemoryRecallGatewayResult recalled = gateway.retrieve(
                    tenantId, userId, generation, query);
            semanticCode = recalled.resultCode();
            if (metrics != null) {
                metrics.recall(recalled.degradationMode(), recalled.resultCode());
                metrics.candidateCount("INDEX", recalled.candidates().size());
            }
            semantic = loadSemantic(
                    tenantId, userId, generation, now, recalled.candidates(),
                    queryTemporalScope(query));
        }

        LinkedHashSet<String> keys = new LinkedHashSet<>();
        globals.forEach(memory -> keys.add(memory.getCanonicalKey()));
        semantic.forEach(candidate -> keys.add(candidate.memory().getCanonicalKey()));
        Set<String> suppressed = keys.isEmpty() ? Set.of() : Set.copyOf(
                suppressionMapper.selectActiveKeys(
                        tenantId, userId, generation,
                        List.copyOf(keys), retrievalProperties.maxCandidates()));
        rejected("SUPPRESSED", suppressed.size());
        List<RecalledMemory> selected = selector.select(
                globals, semantic, suppressed, retrievalProperties.maxSelected());
        if (metrics != null) metrics.candidateCount("SELECTED", selected.size());
        return new UserMemoryRecallResult(
                selected,
                semanticAttempted, semanticCode,
                UserMemoryRecallStatus.AVAILABLE);
    }

    private UserMemoryRecallResult recallCategorySafely(
            long tenantId,
            long userId,
            MemoryCategory category) {
        UserMemorySettingEntity setting = settingMapper.selectOwned(tenantId, userId);
        if (setting == null) {
            return UserMemoryRecallResult.notInitialized();
        }
        if (!Boolean.TRUE.equals(setting.getMemoryEnabled())) {
            return UserMemoryRecallResult.disabled();
        }
        if (setting.getMemoryGeneration() == null
                || setting.getMemoryGeneration() <= 0) {
            return UserMemoryRecallResult.unavailable();
        }
        long generation = setting.getMemoryGeneration();
        LocalDateTime now = now();
        List<UserMemoryEntity> raw = memoryMapper.selectActiveByCategory(
                tenantId, userId, generation, category.name(), now,
                retrievalProperties.maxCandidates());
        List<UserMemoryEntity> valid = raw.stream()
                .filter(memory -> validOwned(
                        memory, tenantId, userId, generation, now))
                .filter(memory -> category.name().equals(memory.getCategory()))
                .toList();
        rejected("OWNER_OR_STATE", raw.size() - valid.size());
        List<String> keys = valid.stream()
                .map(UserMemoryEntity::getCanonicalKey).distinct().toList();
        Set<String> suppressed = keys.isEmpty() ? Set.of() : Set.copyOf(
                suppressionMapper.selectActiveKeys(
                        tenantId, userId, generation, keys,
                        retrievalProperties.maxCandidates()));
        rejected("SUPPRESSED", suppressed.size());
        List<RecalledMemory> selected = selector.selectAuthoritative(
                valid, suppressed, retrievalProperties.maxSelected());
        if (metrics != null) {
            metrics.candidateCount("MYSQL_VALIDATED", valid.size());
            metrics.candidateCount("SELECTED", selected.size());
        }
        return new UserMemoryRecallResult(
                selected, false, "MYSQL_CATEGORY",
                UserMemoryRecallStatus.AVAILABLE);
    }

    private UserMemoryRecallResult recallPredicatesSafely(
            long tenantId,
            long userId,
            List<String> predicateNames,
            MemoryCategory legacyCategory,
            MemoryTemporalScope temporalScope) {
        UserMemorySettingEntity setting = settingMapper.selectOwned(tenantId, userId);
        if (setting == null) {
            return UserMemoryRecallResult.notInitialized();
        }
        if (!Boolean.TRUE.equals(setting.getMemoryEnabled())) {
            return UserMemoryRecallResult.disabled();
        }
        if (setting.getMemoryGeneration() == null
                || setting.getMemoryGeneration() <= 0) {
            return UserMemoryRecallResult.unavailable();
        }
        long generation = setting.getMemoryGeneration();
        LocalDateTime now = now();
        List<UserMemoryEntity> raw = memoryMapper.selectActiveByPredicates(
                tenantId, userId, generation, predicateNames, legacyCategory.name(),
                temporalScope.name(), now,
                retrievalProperties.maxCandidates());
        List<UserMemoryEntity> valid = raw.stream()
                .filter(memory -> validOwned(memory, tenantId, userId, generation, now))
                .filter(memory -> validTemporal(memory, temporalScope, now))
                .filter(memory -> structuredPredicate(memory, predicateNames)
                        || legacyCategory(memory, legacyCategory, temporalScope))
                .toList();
        rejected("OWNER_OR_STATE", raw.size() - valid.size());
        List<String> keys = valid.stream()
                .map(UserMemoryEntity::getCanonicalKey).distinct().toList();
        Set<String> suppressed = keys.isEmpty() ? Set.of() : Set.copyOf(
                suppressionMapper.selectActiveKeys(
                        tenantId, userId, generation, keys,
                        retrievalProperties.maxCandidates()));
        rejected("SUPPRESSED", suppressed.size());
        List<RecalledMemory> selected = selector.selectAuthoritative(
                valid, suppressed, retrievalProperties.maxSelected());
        if (metrics != null) {
            metrics.candidateCount("MYSQL_VALIDATED", valid.size());
            metrics.candidateCount("SELECTED", selected.size());
        }
        return new UserMemoryRecallResult(
                selected, false, "MYSQL_PREDICATE",
                UserMemoryRecallStatus.AVAILABLE);
    }

    private static boolean structuredPredicate(
            UserMemoryEntity memory,
            List<String> predicateNames) {
        return (Integer.valueOf(2).equals(memory.getSchemaVersion())
                || Integer.valueOf(3).equals(memory.getSchemaVersion()))
                && predicateNames.contains(memory.getPredicateName());
    }

    private static boolean legacyCategory(
            UserMemoryEntity memory,
            MemoryCategory category,
            MemoryTemporalScope temporalScope) {
        return memory.getSchemaVersion() == null
                && temporalScope == MemoryTemporalScope.CURRENT
                && category.name().equals(memory.getCategory());
    }

    private static boolean validTemporal(
            UserMemoryEntity memory,
            MemoryTemporalScope temporalScope,
            LocalDateTime now) {
        if (memory.getSchemaVersion() == null
                || Integer.valueOf(2).equals(memory.getSchemaVersion())) {
            return temporalScope != MemoryTemporalScope.HISTORICAL;
        }
        if (!Integer.valueOf(3).equals(memory.getSchemaVersion())
                || memory.getObservedAt() == null) {
            return false;
        }
        final MemoryTemporalScope storedScope;
        try {
            storedScope = MemoryTemporalScope.valueOf(memory.getTemporalScope());
        } catch (RuntimeException invalidScope) {
            return false;
        }
        if (temporalScope != null && temporalScope != storedScope) {
            return false;
        }
        if (memory.getValidFrom() != null && memory.getValidFrom().isAfter(now)) {
            return false;
        }
        if (storedScope == MemoryTemporalScope.CURRENT) {
            return memory.getValidFrom() != null
                    && (memory.getValidTo() == null || memory.getValidTo().isAfter(now));
        }
        return memory.getValidTo() == null || memory.getValidFrom() == null
                || !memory.getValidTo().isBefore(memory.getValidFrom());
    }

    private List<MemorySelectionCandidate> loadSemantic(
            long tenantId,
            long userId,
            long generation,
            LocalDateTime now,
            List<MemoryRecallCandidateSignal> signals,
            MemoryTemporalScope temporalScope) {
        if (signals.isEmpty()) {
            return List.of();
        }
        List<MemoryRecallCandidateSignal> bounded = signals.stream()
                .limit(retrievalProperties.maxCandidates()).toList();
        List<String> ids = bounded.stream()
                .map(MemoryRecallCandidateSignal::memoryId).distinct().toList();
        List<UserMemoryEntity> rows = memoryMapper.selectActiveCandidates(
                tenantId, userId, generation, ids, now);
        Map<String, UserMemoryEntity> byId = new HashMap<>();
        for (UserMemoryEntity row : rows) {
            if (validOwned(row, tenantId, userId, generation, now)
                    && validTemporal(row, temporalScope, now)) {
                byId.put(row.getMemoryId(), row);
            }
        }
        List<MemorySelectionCandidate> result = new ArrayList<>();
        int versionRejected = 0;
        for (MemoryRecallCandidateSignal signal : bounded) {
            UserMemoryEntity row = byId.get(signal.memoryId());
            if (row != null && row.getVersion() != null
                    && row.getVersion() == signal.memoryVersion()) {
                result.add(new MemorySelectionCandidate(
                        row, signal.score(), signal.rank(), false));
            } else if (row != null) {
                versionRejected++;
            }
        }
        rejected("VERSION_MISMATCH", versionRejected);
        rejected("OWNER_OR_STATE", bounded.size() - result.size() - versionRejected);
        if (metrics != null) metrics.candidateCount("MYSQL_VALIDATED", result.size());
        return List.copyOf(result);
    }

    private boolean validGlobal(
            UserMemoryEntity memory,
            long tenantId,
            long userId,
            long generation,
            LocalDateTime now) {
        return validOwned(memory, tenantId, userId, generation, now)
                && validTemporal(memory, MemoryTemporalScope.CURRENT, now)
                && "USER_EXPLICIT".equals(memory.getSourceType())
                && "VISIBLE".equals(memory.getVisibility())
                && GLOBAL_CATEGORIES.contains(memory.getCategory());
    }

    private boolean validOwned(
            UserMemoryEntity memory,
            long tenantId,
            long userId,
            long generation,
            LocalDateTime now) {
        return memory != null
                && Objects.equals(memory.getTenantId(), tenantId)
                && Objects.equals(memory.getUserId(), userId)
                && Objects.equals(memory.getMemoryGeneration(), generation)
                && memory.getMemoryId() != null && !memory.getMemoryId().isBlank()
                && memory.getVersion() != null && memory.getVersion() > 0
                && SOURCES.contains(memory.getSourceType())
                && (("USER_EXPLICIT".equals(memory.getSourceType())
                        && "VISIBLE".equals(memory.getVisibility()))
                    || ("AUTO_EXTRACT".equals(memory.getSourceType())
                        && "HIDDEN".equals(memory.getVisibility())))
                && memory.getCategory() != null && !memory.getCategory().isBlank()
                && memory.getCanonicalKey() != null && !memory.getCanonicalKey().isBlank()
                && memory.getContent() != null && !memory.getContent().isBlank()
                && memory.getContent().length() <= memoryProperties.maxContentLength()
                && memory.getConfidence() != null
                && memory.getConfidence().signum() >= 0
                && memory.getConfidence().compareTo(java.math.BigDecimal.ONE) <= 0
                && "ACTIVE".equals(memory.getStatus())
                && (memory.getExpiresAt() == null || memory.getExpiresAt().isAfter(now));
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(
                clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
    }

    /** 未明确询问历史时按当前事实处理；同时询问过去和现在时允许两类候选。 */
    private static MemoryTemporalScope queryTemporalScope(String query) {
        boolean historical = containsAny(query,
                "以前", "过去", "曾经", "原来", "之前", "历史");
        boolean current = containsAny(query,
                "现在", "目前", "当前", "如今", "现任");
        if (historical && current) {
            return null;
        }
        return historical ? MemoryTemporalScope.HISTORICAL : MemoryTemporalScope.CURRENT;
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private void rejected(String reason, int count) {
        if (metrics != null) metrics.mysqlRejected(reason, count);
    }
}
