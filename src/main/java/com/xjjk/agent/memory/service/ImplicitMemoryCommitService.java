package com.xjjk.agent.memory.service;

import com.xjjk.agent.memory.config.ImplicitMemoryProperties;
import com.xjjk.agent.memory.domain.ImplicitMemoryExtractionBatch;
import com.xjjk.agent.memory.domain.MemoryExtractionResultCode;
import com.xjjk.agent.memory.domain.MemoryExtractionTaskClaim;
import com.xjjk.agent.memory.domain.MemoryOutboxOperation;
import com.xjjk.agent.memory.domain.MemoryOutboxStatus;
import com.xjjk.agent.memory.domain.MemoryRetentionType;
import com.xjjk.agent.memory.domain.MemorySourceType;
import com.xjjk.agent.memory.domain.MemoryStatus;
import com.xjjk.agent.memory.domain.MemoryVisibility;
import com.xjjk.agent.memory.domain.ValidatedMemoryFact;
import com.xjjk.agent.memory.persistence.entity.MemoryExtractionTaskEntity;
import com.xjjk.agent.memory.persistence.entity.MemoryOutboxEntity;
import com.xjjk.agent.memory.persistence.entity.UserMemoryEntity;
import com.xjjk.agent.memory.persistence.entity.UserMemorySettingEntity;
import com.xjjk.agent.memory.persistence.mapper.MemoryExtractionTaskMapper;
import com.xjjk.agent.memory.persistence.mapper.MemoryOutboxMapper;
import com.xjjk.agent.memory.persistence.mapper.MemorySuppressionMapper;
import com.xjjk.agent.memory.persistence.mapper.UserMemoryMapper;
import com.xjjk.agent.memory.persistence.mapper.UserMemorySettingMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 将已校验候选在短事务内提交为隐藏自动记忆。 */
@Service
public class ImplicitMemoryCommitService {

    private final MemoryExtractionTaskMapper taskMapper;
    private final UserMemorySettingMapper settingMapper;
    private final UserMemoryMapper memoryMapper;
    private final MemorySuppressionMapper suppressionMapper;
    private final MemoryOutboxMapper outboxMapper;
    private final ImplicitMemoryProperties properties;
    private final Clock clock;

    @Autowired
    public ImplicitMemoryCommitService(
            MemoryExtractionTaskMapper taskMapper,
            UserMemorySettingMapper settingMapper,
            UserMemoryMapper memoryMapper,
            MemorySuppressionMapper suppressionMapper,
            MemoryOutboxMapper outboxMapper,
            ImplicitMemoryProperties properties
    ) {
        this(taskMapper, settingMapper, memoryMapper, suppressionMapper, outboxMapper,
                properties, Clock.systemUTC());
    }

    ImplicitMemoryCommitService(
            MemoryExtractionTaskMapper taskMapper,
            UserMemorySettingMapper settingMapper,
            UserMemoryMapper memoryMapper,
            MemorySuppressionMapper suppressionMapper,
            MemoryOutboxMapper outboxMapper,
            ImplicitMemoryProperties properties,
            Clock clock
    ) {
        this.taskMapper = Objects.requireNonNull(taskMapper, "taskMapper");
        this.settingMapper = Objects.requireNonNull(settingMapper, "settingMapper");
        this.memoryMapper = Objects.requireNonNull(memoryMapper, "memoryMapper");
        this.suppressionMapper = Objects.requireNonNull(suppressionMapper, "suppressionMapper");
        this.outboxMapper = Objects.requireNonNull(outboxMapper, "outboxMapper");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public int commit(MemoryExtractionTaskClaim claim, ImplicitMemoryExtractionBatch batch) {
        Objects.requireNonNull(claim, "claim");
        Objects.requireNonNull(batch, "batch");
        LocalDateTime now = now();
        MemoryExtractionTaskEntity task = taskMapper.selectLeaseForUpdate(
                claim.id(), claim.leaseToken(), claim.lockedBy());
        if (!matches(task, claim)) {
            return 0;
        }
        UserMemorySettingEntity setting = settingMapper.selectOwnedForUpdate(
                claim.tenantId(), claim.userId());
        if (setting == null
                || !Boolean.TRUE.equals(setting.getMemoryEnabled())
                || !Boolean.TRUE.equals(setting.getAutoExtractEnabled())) {
            cancel(claim, "MEMORY_DISABLED", now);
            return 0;
        }
        if (!Objects.equals(setting.getMemoryGeneration(), claim.memoryGeneration())) {
            cancel(claim, "STALE_GENERATION", now);
            return 0;
        }

        int saved = 0;
        for (ValidatedMemoryFact candidate : deduplicate(batch.acceptedCandidates())) {
            if (suppressionMapper.existsOwnedActive(
                    claim.tenantId(), claim.userId(), claim.memoryGeneration(),
                    candidate.canonicalKey())) {
                continue;
            }
            UserMemoryEntity previous = memoryMapper.selectActiveByKeyForUpdate(
                    claim.tenantId(), claim.userId(), claim.memoryGeneration(),
                    candidate.canonicalKey());
            if (previous != null && MemorySourceType.USER_EXPLICIT.name()
                    .equals(previous.getSourceType())) {
                continue;
            }
            long version = previous == null || previous.getVersion() == null
                    ? 1L : Math.addExact(previous.getVersion(), 1L);
            if (previous != null) {
                if (memoryMapper.supersedeOwnedActive(
                        claim.tenantId(), claim.userId(), claim.memoryGeneration(),
                        candidate.canonicalKey(), now) != 1) {
                    throw new IllegalStateException("隐式记忆旧版本失效失败");
                }
                insertOutbox(claim, previous.getMemoryId(), previous.getVersion(),
                        MemoryOutboxOperation.DELETE, now);
            }
            String memoryId = UUID.randomUUID().toString();
            UserMemoryEntity memory = toEntity(claim, candidate, memoryId, version, now);
            if (memoryMapper.insert(memory) != 1) {
                throw new IllegalStateException("隐式记忆写入失败");
            }
            insertOutbox(claim, memoryId, version, MemoryOutboxOperation.UPSERT, now);
            saved++;
        }
        MemoryExtractionResultCode resultCode = batch.resultCodeFor(saved);
        if (taskMapper.completeLease(
                claim.id(), claim.leaseToken(), claim.lockedBy(), resultCode.name(),
                batch.modelCandidateCount(), batch.acceptedCandidates().size(), saved, now) != 1) {
            throw new IllegalStateException("隐式记忆任务完成失败");
        }
        return saved;
    }

    private UserMemoryEntity toEntity(
            MemoryExtractionTaskClaim claim,
            ValidatedMemoryFact candidate,
            String memoryId,
            long version,
            LocalDateTime now
    ) {
        UserMemoryEntity memory = new UserMemoryEntity();
        memory.setMemoryId(memoryId);
        memory.setTenantId(claim.tenantId());
        memory.setUserId(claim.userId());
        memory.setMemoryGeneration(claim.memoryGeneration());
        memory.setSourceType(MemorySourceType.AUTO_EXTRACT.name());
        memory.setCategory(candidate.legacyCategory());
        memory.setSchemaVersion(2);
        memory.setMemoryType(candidate.candidate().memoryType().name());
        memory.setPredicateName(candidate.candidate().predicate());
        memory.setValueJson(candidate.valueJson());
        memory.setStability(candidate.candidate().stability().name());
        memory.setVerificationMethod(candidate.verificationMethod());
        memory.setCanonicalKey(candidate.canonicalKey());
        memory.setContent(candidate.canonicalContent());
        memory.setContentHash(MemoryHashing.sha256(candidate.canonicalContent()));
        memory.setConfidence(BigDecimal.valueOf(candidate.confidence())
                .setScale(4, RoundingMode.HALF_UP));
        memory.setVisibility(MemoryVisibility.HIDDEN.name());
        memory.setRetentionType(MemoryRetentionType.NORMAL.name());
        memory.setStatus(MemoryStatus.ACTIVE.name());
        memory.setSourceConversationId(claim.conversationId());
        memory.setSourceMessageSequence(claim.userMessageSequence());
        memory.setEvidenceText(candidate.evidenceText());
        memory.setVersion(version);
        memory.setExpiresAt(now.plusDays(properties.expireDays()));
        memory.setCreatedAt(now);
        memory.setUpdatedAt(now);
        return memory;
    }

    private void insertOutbox(
            MemoryExtractionTaskClaim claim,
            String memoryId,
            long version,
            MemoryOutboxOperation operation,
            LocalDateTime now
    ) {
        if (memoryId == null || version < 1) {
            throw new IllegalStateException("隐式记忆索引事件来源无效");
        }
        MemoryOutboxEntity outbox = new MemoryOutboxEntity();
        outbox.setEventId(UUID.randomUUID().toString());
        outbox.setMemoryId(memoryId);
        outbox.setTenantId(claim.tenantId());
        outbox.setUserId(claim.userId());
        outbox.setMemoryGeneration(claim.memoryGeneration());
        outbox.setMemoryVersion(version);
        outbox.setOperation(operation.name());
        outbox.setStatus(MemoryOutboxStatus.PENDING.name());
        outbox.setRetryCount(0);
        outbox.setNextRunAt(now);
        outbox.setCreatedAt(now);
        outbox.setUpdatedAt(now);
        if (outboxMapper.insert(outbox) != 1) {
            throw new IllegalStateException("隐式记忆索引事件写入失败");
        }
    }

    private void cancel(MemoryExtractionTaskClaim claim, String code, LocalDateTime now) {
        if (taskMapper.cancelLease(
                claim.id(), claim.leaseToken(), claim.lockedBy(), code, now) != 1) {
            throw new IllegalStateException("隐式记忆任务取消失败");
        }
    }

    private static List<ValidatedMemoryFact> deduplicate(List<ValidatedMemoryFact> candidates) {
        Map<String, ValidatedMemoryFact> byKey = new LinkedHashMap<>();
        candidates.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingDouble(ValidatedMemoryFact::confidence).reversed())
                .forEach(candidate -> byKey.putIfAbsent(candidate.canonicalKey(), candidate));
        return List.copyOf(byKey.values());
    }

    private static boolean matches(MemoryExtractionTaskEntity task, MemoryExtractionTaskClaim claim) {
        return task != null
                && Objects.equals(task.getTaskId(), claim.taskId())
                && Objects.equals(task.getTenantId(), claim.tenantId())
                && Objects.equals(task.getUserId(), claim.userId())
                && Objects.equals(task.getConversationId(), claim.conversationId())
                && Objects.equals(task.getRequestId(), claim.requestId())
                && Objects.equals(task.getUserMessageId(), claim.userMessageId())
                && Objects.equals(task.getUserMessageSequence(), claim.userMessageSequence())
                && Objects.equals(task.getMemoryGeneration(), claim.memoryGeneration());
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(
                clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
    }
}
