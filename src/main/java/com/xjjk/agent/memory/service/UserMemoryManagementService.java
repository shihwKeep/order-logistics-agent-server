package com.xjjk.agent.memory.service;

import com.xjjk.agent.common.api.ApiErrorCode;
import com.xjjk.agent.common.exception.BusinessException;
import com.xjjk.agent.identity.domain.AgentIdentity;
import com.xjjk.agent.memory.config.UserMemoryProperties;
import com.xjjk.agent.memory.domain.MemoryOutboxOperation;
import com.xjjk.agent.memory.domain.MemoryCategory;
import com.xjjk.agent.memory.domain.MemoryOutboxStatus;
import com.xjjk.agent.memory.domain.MemoryRetentionType;
import com.xjjk.agent.memory.domain.MemoryStatus;
import com.xjjk.agent.memory.domain.MemorySuppressionStatus;
import com.xjjk.agent.memory.persistence.entity.MemoryOutboxEntity;
import com.xjjk.agent.memory.persistence.entity.MemorySuppressionEntity;
import com.xjjk.agent.memory.persistence.entity.UserMemoryEntity;
import com.xjjk.agent.memory.persistence.entity.UserMemorySettingEntity;
import com.xjjk.agent.memory.persistence.mapper.MemoryOutboxMapper;
import com.xjjk.agent.memory.persistence.mapper.MemorySuppressionMapper;
import com.xjjk.agent.memory.persistence.mapper.UserMemoryMapper;
import com.xjjk.agent.memory.persistence.mapper.UserMemorySettingMapper;
import com.xjjk.agent.memory.observation.UserMemoryMetrics;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

@Service
public class UserMemoryManagementService {

    private final UserMemorySettingMapper settingMapper;
    private final UserMemoryMapper memoryMapper;
    private final MemorySuppressionMapper suppressionMapper;
    private final MemoryOutboxMapper outboxMapper;
    private final MemorySensitiveContentPolicy sensitivePolicy;
    private final MemoryCategoryContentPolicy categoryContentPolicy;
    private final UserMemoryProperties properties;
    private final Clock clock;
    private final UserMemoryMetrics metrics;

    @Autowired
    public UserMemoryManagementService(
            UserMemorySettingMapper settingMapper,
            UserMemoryMapper memoryMapper,
            MemorySuppressionMapper suppressionMapper,
            MemoryOutboxMapper outboxMapper,
            MemorySensitiveContentPolicy sensitivePolicy,
            MemoryCategoryContentPolicy categoryContentPolicy,
            UserMemoryProperties properties,
            UserMemoryMetrics metrics
    ) {
        this(settingMapper, memoryMapper, suppressionMapper, outboxMapper,
                sensitivePolicy, categoryContentPolicy, properties, metrics, Clock.systemUTC());
    }

    UserMemoryManagementService(
            UserMemorySettingMapper settingMapper,
            UserMemoryMapper memoryMapper,
            MemorySuppressionMapper suppressionMapper,
            MemoryOutboxMapper outboxMapper,
            MemorySensitiveContentPolicy sensitivePolicy,
            MemoryCategoryContentPolicy categoryContentPolicy,
            UserMemoryProperties properties,
            UserMemoryMetrics metrics,
            Clock clock
    ) {
        this.settingMapper = Objects.requireNonNull(settingMapper);
        this.memoryMapper = Objects.requireNonNull(memoryMapper);
        this.suppressionMapper = Objects.requireNonNull(suppressionMapper);
        this.outboxMapper = Objects.requireNonNull(outboxMapper);
        this.sensitivePolicy = Objects.requireNonNull(sensitivePolicy);
        this.categoryContentPolicy = Objects.requireNonNull(categoryContentPolicy);
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
        this.metrics = Objects.requireNonNull(metrics);
    }

    @Transactional
    public EditResult edit(
            AgentIdentity identity,
            String memoryId,
            String requestedContent,
            MemoryRetentionType retentionType
    ) {
        return observe("edit", ApiErrorCode.MEMORY_WRITE_FAILED,
                () -> doEdit(identity, memoryId, requestedContent, retentionType), ignored -> 1);
    }

    private EditResult doEdit(AgentIdentity identity, String memoryId, String requestedContent,
                              MemoryRetentionType retentionType) {
        String content = ExplicitMemoryCommandDetector.normalizeWhitespace(requestedContent == null ? "" : requestedContent);
        if (content.isBlank()
                || content.codePointCount(0, content.length()) > properties.maxContentLength()
                || retentionType == null
                || !sensitivePolicy.isAllowed(content)) {
            throw new BusinessException(ApiErrorCode.MEMORY_CONTENT_REJECTED);
        }
        LocalDateTime now = now();
        long generation = lockSetting(identity, now).getMemoryGeneration();
        UserMemoryEntity current = memoryMapper.selectOwnedVisibleExplicitForUpdate(
                identity.tenantId(), identity.userId(), generation, memoryId);
        if (current == null) {
            throw new BusinessException(ApiErrorCode.MEMORY_NOT_FOUND);
        }
        content = canonicalizeForExistingCategory(current, content);
        if (memoryMapper.supersedeOwnedActive(identity.tenantId(), identity.userId(), generation,
                current.getCanonicalKey(), now) != 1) {
            throw new BusinessException(ApiErrorCode.MEMORY_WRITE_FAILED);
        }
        suppressionMapper.liftOwnedActive(identity.tenantId(), identity.userId(), generation,
                current.getCanonicalKey(), now);

        String newMemoryId = UUID.randomUUID().toString();
        UserMemoryEntity replacement = copyForEdit(current, newMemoryId, content, retentionType, now);
        if (memoryMapper.insert(replacement) != 1) {
            throw new BusinessException(ApiErrorCode.MEMORY_WRITE_FAILED);
        }
        insertOutbox(identity, generation, current.getVersion(), current.getMemoryId(),
                MemoryOutboxOperation.DELETE, now, ApiErrorCode.MEMORY_WRITE_FAILED);
        insertOutbox(identity, generation, replacement.getVersion(), newMemoryId,
                MemoryOutboxOperation.UPSERT, now, ApiErrorCode.MEMORY_WRITE_FAILED);
        return new EditResult(newMemoryId, replacement.getVersion());
    }

    private String canonicalizeForExistingCategory(UserMemoryEntity current, String content) {
        try {
            return categoryContentPolicy.canonicalize(MemoryCategory.valueOf(current.getCategory()), content)
                    .orElseThrow(() -> new BusinessException(ApiErrorCode.MEMORY_CONTENT_REJECTED));
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new BusinessException(ApiErrorCode.MEMORY_CONTENT_REJECTED);
        }
    }

    @Transactional
    public MutationResult delete(AgentIdentity identity, String memoryId) {
        return observe("delete", ApiErrorCode.MEMORY_WRITE_FAILED,
                () -> doDelete(identity, memoryId), MutationResult::affectedCount);
    }

    private MutationResult doDelete(AgentIdentity identity, String memoryId) {
        LocalDateTime now = now();
        long generation = lockSetting(identity, now).getMemoryGeneration();
        UserMemoryEntity current = memoryMapper.selectOwnedVisibleExplicitForUpdate(
                identity.tenantId(), identity.userId(), generation, memoryId);
        if (current == null) {
            throw new BusinessException(ApiErrorCode.MEMORY_NOT_FOUND);
        }
        if (memoryMapper.softDeleteOwned(identity.tenantId(), identity.userId(), generation,
                memoryId, now) != 1) {
            throw new BusinessException(ApiErrorCode.MEMORY_NOT_FOUND);
        }
        insertSuppression(identity, generation, current, now);
        insertOutbox(identity, generation, current.getVersion(), memoryId,
                MemoryOutboxOperation.DELETE, now, ApiErrorCode.MEMORY_WRITE_FAILED);
        return new MutationResult(1, generation);
    }

    @Transactional
    public MutationResult clearExplicit(AgentIdentity identity) {
        return observe("clear_explicit", ApiErrorCode.MEMORY_CLEAR_FAILED,
                () -> doClearExplicit(identity), MutationResult::affectedCount);
    }

    private MutationResult doClearExplicit(AgentIdentity identity) {
        LocalDateTime now = now();
        long generation = lockSetting(identity, now).getMemoryGeneration();
        List<UserMemoryEntity> active = memoryMapper.selectAllOwnedVisibleExplicitForUpdate(
                identity.tenantId(), identity.userId(), generation);
        for (UserMemoryEntity memory : active) {
            insertSuppression(identity, generation, memory, now);
        }
        int affected = memoryMapper.clearOwnedExplicit(
                identity.tenantId(), identity.userId(), generation, now);
        if (affected != active.size()) {
            throw new BusinessException(ApiErrorCode.MEMORY_CLEAR_FAILED);
        }
        insertOutbox(identity, generation, 0L, null,
                MemoryOutboxOperation.DELETE_EXPLICIT_SCOPE, now, ApiErrorCode.MEMORY_CLEAR_FAILED);
        return new MutationResult(affected, generation);
    }

    @Transactional
    public MutationResult clearAll(AgentIdentity identity) {
        return observe("clear_all", ApiErrorCode.MEMORY_CLEAR_FAILED,
                () -> doClearAll(identity), MutationResult::affectedCount);
    }

    private MutationResult doClearAll(AgentIdentity identity) {
        LocalDateTime now = now();
        long generation = lockSetting(identity, now).getMemoryGeneration();
        final long nextGeneration;
        try {
            nextGeneration = Math.incrementExact(generation);
        } catch (ArithmeticException exception) {
            throw new BusinessException(ApiErrorCode.MEMORY_CLEAR_FAILED);
        }
        int affected = memoryMapper.clearOwnedGeneration(
                identity.tenantId(), identity.userId(), generation, now);
        suppressionMapper.liftOwnedGeneration(
                identity.tenantId(), identity.userId(), generation, now);
        if (settingMapper.compareAndIncrementGeneration(
                identity.tenantId(), identity.userId(), generation, now) != 1) {
            throw new BusinessException(ApiErrorCode.MEMORY_CLEAR_FAILED);
        }
        insertOutbox(identity, generation, 0L, null,
                MemoryOutboxOperation.CLEAR_GENERATION, now, ApiErrorCode.MEMORY_CLEAR_FAILED);
        return new MutationResult(affected, nextGeneration);
    }

    private UserMemorySettingEntity lockSetting(AgentIdentity identity, LocalDateTime now) {
        settingMapper.insertIfAbsent(identity.tenantId(), identity.userId(),
                properties.autoExtractDefaultEnabled(), now);
        UserMemorySettingEntity setting = settingMapper.selectOwnedForUpdate(
                identity.tenantId(), identity.userId());
        if (setting == null || setting.getMemoryGeneration() == null) {
            throw new BusinessException(ApiErrorCode.MEMORY_WRITE_FAILED);
        }
        return setting;
    }

    private UserMemoryEntity copyForEdit(UserMemoryEntity current, String memoryId,
                                         String content, MemoryRetentionType retention, LocalDateTime now) {
        UserMemoryEntity replacement = new UserMemoryEntity();
        replacement.setMemoryId(memoryId);
        replacement.setTenantId(current.getTenantId());
        replacement.setUserId(current.getUserId());
        replacement.setMemoryGeneration(current.getMemoryGeneration());
        replacement.setSourceType(current.getSourceType());
        replacement.setCategory(current.getCategory());
        replacement.setCanonicalKey(current.getCanonicalKey());
        replacement.setContent(content);
        replacement.setContentHash(MemoryHashing.sha256(content));
        replacement.setConfidence(current.getConfidence());
        replacement.setVisibility(current.getVisibility());
        replacement.setRetentionType(retention.name());
        replacement.setStatus(MemoryStatus.ACTIVE.name());
        // API 编辑是新的直接用户证据，不能伪装成旧会话中的消息来源。
        replacement.setSourceConversationId(null);
        replacement.setSourceMessageSequence(null);
        replacement.setEvidenceText(content);
        replacement.setVersion(Math.incrementExact(current.getVersion()));
        replacement.setExpiresAt(retention == MemoryRetentionType.PERMANENT
                ? null : now.plusDays(properties.explicitExpireDays()));
        replacement.setCreatedAt(now);
        replacement.setUpdatedAt(now);
        return replacement;
    }

    private void insertSuppression(AgentIdentity identity, long generation,
                                   UserMemoryEntity memory, LocalDateTime now) {
        MemorySuppressionEntity suppression = new MemorySuppressionEntity();
        suppression.setSuppressionId(UUID.randomUUID().toString());
        suppression.setTenantId(identity.tenantId());
        suppression.setUserId(identity.userId());
        suppression.setMemoryGeneration(generation);
        suppression.setCanonicalKey(memory.getCanonicalKey());
        suppression.setContentHash(memory.getContentHash());
        suppression.setStatus(MemorySuppressionStatus.ACTIVE.name());
        suppression.setCreatedAt(now);
        suppression.setUpdatedAt(now);
        if (suppressionMapper.insertOwned(suppression) != 1) {
            throw new BusinessException(ApiErrorCode.MEMORY_CLEAR_FAILED);
        }
    }

    private void insertOutbox(AgentIdentity identity, long generation, long version,
                              String memoryId, MemoryOutboxOperation operation,
                              LocalDateTime now, ApiErrorCode errorCode) {
        MemoryOutboxEntity outbox = new MemoryOutboxEntity();
        outbox.setEventId(UUID.randomUUID().toString());
        outbox.setMemoryId(memoryId);
        outbox.setTenantId(identity.tenantId());
        outbox.setUserId(identity.userId());
        outbox.setMemoryGeneration(generation);
        outbox.setMemoryVersion(version);
        outbox.setOperation(operation.name());
        outbox.setStatus(MemoryOutboxStatus.PENDING.name());
        outbox.setRetryCount(0);
        outbox.setNextRunAt(now);
        outbox.setCreatedAt(now);
        outbox.setUpdatedAt(now);
        if (outboxMapper.insert(outbox) != 1) {
            throw new BusinessException(errorCode);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
    }

    private <T> T observe(String operation, ApiErrorCode rollbackCode,
                          Supplier<T> action, ToIntFunction<T> affectedCount) {
        try {
            T result = action.get();
            recordSuccessAfterCommit(operation, rollbackCode, affectedCount.applyAsInt(result));
            return result;
        } catch (BusinessException exception) {
            if (exception.errorCode() == ApiErrorCode.MEMORY_CONTENT_REJECTED
                    || exception.errorCode() == ApiErrorCode.MEMORY_NOT_FOUND) {
                metrics.rejected(operation, exception.errorCode());
            } else {
                metrics.failure(operation, exception.errorCode());
            }
            throw exception;
        } catch (RuntimeException exception) {
            metrics.failure(operation, ApiErrorCode.INTERNAL_SERVER_ERROR);
            throw exception;
        }
    }

    private void recordSuccessAfterCommit(String operation, ApiErrorCode rollbackCode,
                                          int affectedCount) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            metrics.success(operation, affectedCount);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            private boolean committed;

            @Override
            public void afterCommit() {
                committed = true;
                metrics.success(operation, affectedCount);
            }

            @Override
            public void afterCompletion(int status) {
                if (!committed && status != STATUS_COMMITTED) {
                    metrics.failure(operation, rollbackCode);
                }
            }
        });
    }

    public record EditResult(String memoryId, long version) {}
    public record MutationResult(int affectedCount, long generation) {}
}
