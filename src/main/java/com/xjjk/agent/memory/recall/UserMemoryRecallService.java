package com.xjjk.agent.memory.recall;

import com.xjjk.agent.identity.domain.AgentIdentity;
import com.xjjk.agent.memory.config.MemoryRetrievalProperties;
import com.xjjk.agent.memory.config.UserMemoryProperties;
import com.xjjk.agent.memory.persistence.entity.UserMemoryEntity;
import com.xjjk.agent.memory.persistence.entity.UserMemorySettingEntity;
import com.xjjk.agent.memory.persistence.mapper.MemorySuppressionMapper;
import com.xjjk.agent.memory.persistence.mapper.UserMemoryMapper;
import com.xjjk.agent.memory.persistence.mapper.UserMemorySettingMapper;
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

    @Autowired
    public UserMemoryRecallService(
            UserMemorySettingMapper settingMapper,
            UserMemoryMapper memoryMapper,
            MemorySuppressionMapper suppressionMapper,
            MemoryRecallGateway gateway,
            MemoryRecallGate gate,
            MemoryCandidateSelector selector,
            UserMemoryProperties memoryProperties,
            MemoryRetrievalProperties retrievalProperties) {
        this(settingMapper, memoryMapper, suppressionMapper, gateway, gate, selector,
                memoryProperties, retrievalProperties, Clock.systemUTC());
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
    }

    public UserMemoryRecallResult recall(AgentIdentity identity, String query) {
        Objects.requireNonNull(identity, "identity");
        if (identity.tenantId() <= 0 || identity.userId() <= 0
                || query == null || query.isBlank() || query.length() > 2_000) {
            return UserMemoryRecallResult.empty();
        }
        if (!memoryProperties.enabled()) {
            return UserMemoryRecallResult.empty();
        }
        try {
            return recallSafely(identity, query.strip());
        } catch (RuntimeException failure) {
            // 记忆是可选增强；数据库终审异常时整批失效，不能阻断普通聊天。
            log.warn("user_memory_recall result=DEGRADED errorCode=MYSQL_VALIDATION_FAILED exceptionType={}",
                    failure.getClass().getSimpleName());
            return UserMemoryRecallResult.empty();
        }
    }

    private UserMemoryRecallResult recallSafely(AgentIdentity identity, String query) {
        UserMemorySettingEntity setting = settingMapper.selectOwned(
                identity.tenantId(), identity.userId());
        if (setting == null || !Boolean.TRUE.equals(setting.getMemoryEnabled())
                || setting.getMemoryGeneration() == null
                || setting.getMemoryGeneration() <= 0) {
            return UserMemoryRecallResult.empty();
        }
        long generation = setting.getMemoryGeneration();
        LocalDateTime now = now();
        List<UserMemoryEntity> globals = memoryMapper.selectGlobalExplicit(
                identity.tenantId(), identity.userId(), generation, now,
                retrievalProperties.globalExplicitLimit()).stream()
                .filter(memory -> validGlobal(memory, identity, generation, now))
                .toList();

        boolean semanticAttempted = gate.shouldRetrieve(query);
        String semanticCode = semanticAttempted ? "NO_CANDIDATE" : "SKIPPED";
        List<MemorySelectionCandidate> semantic = List.of();
        if (semanticAttempted) {
            MemoryRecallGatewayResult recalled = gateway.retrieve(
                    identity.tenantId(), identity.userId(), generation, query);
            semanticCode = recalled.resultCode();
            semantic = loadSemantic(identity, generation, now, recalled.candidates());
        }

        LinkedHashSet<String> keys = new LinkedHashSet<>();
        globals.forEach(memory -> keys.add(memory.getCanonicalKey()));
        semantic.forEach(candidate -> keys.add(candidate.memory().getCanonicalKey()));
        Set<String> suppressed = keys.isEmpty() ? Set.of() : Set.copyOf(
                suppressionMapper.selectActiveKeys(
                        identity.tenantId(), identity.userId(), generation,
                        List.copyOf(keys), retrievalProperties.maxCandidates()));
        return new UserMemoryRecallResult(
                selector.select(globals, semantic, suppressed,
                        retrievalProperties.maxSelected()),
                semanticAttempted, semanticCode);
    }

    private List<MemorySelectionCandidate> loadSemantic(
            AgentIdentity identity,
            long generation,
            LocalDateTime now,
            List<MemoryRecallCandidateSignal> signals) {
        if (signals.isEmpty()) {
            return List.of();
        }
        List<MemoryRecallCandidateSignal> bounded = signals.stream()
                .limit(retrievalProperties.maxCandidates()).toList();
        List<String> ids = bounded.stream()
                .map(MemoryRecallCandidateSignal::memoryId).distinct().toList();
        List<UserMemoryEntity> rows = memoryMapper.selectActiveCandidates(
                identity.tenantId(), identity.userId(), generation, ids, now);
        Map<String, UserMemoryEntity> byId = new HashMap<>();
        for (UserMemoryEntity row : rows) {
            if (validOwned(row, identity, generation, now)) {
                byId.put(row.getMemoryId(), row);
            }
        }
        List<MemorySelectionCandidate> result = new ArrayList<>();
        for (MemoryRecallCandidateSignal signal : bounded) {
            UserMemoryEntity row = byId.get(signal.memoryId());
            if (row != null && row.getVersion() != null
                    && row.getVersion() == signal.memoryVersion()) {
                result.add(new MemorySelectionCandidate(
                        row, signal.score(), signal.rank(), false));
            }
        }
        return List.copyOf(result);
    }

    private boolean validGlobal(
            UserMemoryEntity memory,
            AgentIdentity identity,
            long generation,
            LocalDateTime now) {
        return validOwned(memory, identity, generation, now)
                && "USER_EXPLICIT".equals(memory.getSourceType())
                && "VISIBLE".equals(memory.getVisibility())
                && GLOBAL_CATEGORIES.contains(memory.getCategory());
    }

    private boolean validOwned(
            UserMemoryEntity memory,
            AgentIdentity identity,
            long generation,
            LocalDateTime now) {
        return memory != null
                && Objects.equals(memory.getTenantId(), identity.tenantId())
                && Objects.equals(memory.getUserId(), identity.userId())
                && Objects.equals(memory.getMemoryGeneration(), generation)
                && memory.getMemoryId() != null && !memory.getMemoryId().isBlank()
                && memory.getVersion() != null && memory.getVersion() > 0
                && SOURCES.contains(memory.getSourceType())
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
}
