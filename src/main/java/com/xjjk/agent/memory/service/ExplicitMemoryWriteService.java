package com.xjjk.agent.memory.service;

import com.xjjk.agent.chat.domain.ChatTurnContext;
import com.xjjk.agent.chat.persistence.mapper.AgentMessageMapper;
import com.xjjk.agent.common.api.ApiErrorCode;
import com.xjjk.agent.common.exception.BusinessException;
import com.xjjk.agent.memory.config.UserMemoryProperties;
import com.xjjk.agent.memory.domain.ExplicitMemoryCandidate;
import com.xjjk.agent.memory.domain.MemoryFactCandidate;
import com.xjjk.agent.memory.domain.MemoryOutboxOperation;
import com.xjjk.agent.memory.domain.MemoryOutboxStatus;
import com.xjjk.agent.memory.domain.MemoryRetentionType;
import com.xjjk.agent.memory.domain.MemorySourceType;
import com.xjjk.agent.memory.domain.MemoryStatus;
import com.xjjk.agent.memory.domain.MemoryVisibility;
import com.xjjk.agent.memory.domain.MemoryStability;
import com.xjjk.agent.memory.domain.MemoryType;
import com.xjjk.agent.memory.persistence.entity.MemoryOutboxEntity;
import com.xjjk.agent.memory.persistence.entity.UserMemoryEntity;
import com.xjjk.agent.memory.persistence.entity.UserMemorySettingEntity;
import com.xjjk.agent.memory.persistence.mapper.MemoryOutboxMapper;
import com.xjjk.agent.memory.persistence.mapper.MemorySuppressionMapper;
import com.xjjk.agent.memory.persistence.mapper.UserMemoryMapper;
import com.xjjk.agent.memory.persistence.mapper.UserMemorySettingMapper;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

@Service
public class ExplicitMemoryWriteService {

    private final UserMemorySettingMapper settingMapper;
    private final UserMemoryMapper memoryMapper;
    private final MemorySuppressionMapper suppressionMapper;
    private final MemoryOutboxMapper outboxMapper;
    private final AgentMessageMapper messageMapper;
    private final UserMemoryProperties properties;
    private final UserMemoryPolicyService policy;
    private final MemorySchemaRegistry schemaRegistry;
    private final Clock clock;

    @Autowired
    public ExplicitMemoryWriteService(
            UserMemorySettingMapper settingMapper,
            UserMemoryMapper memoryMapper,
            MemorySuppressionMapper suppressionMapper,
            MemoryOutboxMapper outboxMapper,
            AgentMessageMapper messageMapper,
            UserMemoryProperties properties,
            UserMemoryPolicyService policy,
            MemorySchemaRegistry schemaRegistry
    ) {
        this(settingMapper, memoryMapper, suppressionMapper, outboxMapper,
                messageMapper, properties, policy, schemaRegistry, Clock.systemUTC());
    }

    ExplicitMemoryWriteService(
            UserMemorySettingMapper settingMapper,
            UserMemoryMapper memoryMapper,
            MemorySuppressionMapper suppressionMapper,
            MemoryOutboxMapper outboxMapper,
            AgentMessageMapper messageMapper,
            UserMemoryProperties properties,
            UserMemoryPolicyService policy,
            MemorySchemaRegistry schemaRegistry,
            Clock clock
    ) {
        this.settingMapper = Objects.requireNonNull(settingMapper, "settingMapper");
        this.memoryMapper = Objects.requireNonNull(memoryMapper, "memoryMapper");
        this.suppressionMapper = Objects.requireNonNull(suppressionMapper, "suppressionMapper");
        this.outboxMapper = Objects.requireNonNull(outboxMapper, "outboxMapper");
        this.messageMapper = Objects.requireNonNull(messageMapper, "messageMapper");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.schemaRegistry = Objects.requireNonNull(schemaRegistry, "schemaRegistry");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Transactional
    public SaveResult save(ChatTurnContext turn, ExplicitMemoryCandidate candidate) {
        Objects.requireNonNull(turn, "turn");
        Objects.requireNonNull(candidate, "candidate");
        LocalDateTime now = LocalDateTime.ofInstant(
                clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);

        int settingInsert = settingMapper.insertIfAbsent(
                turn.tenantId(), turn.userId(), true,
                properties.autoExtractDefaultEnabled(), now);
        if (settingInsert < 0 || settingInsert > 1) {
            throw writeFailed();
        }
        UserMemorySettingEntity setting = settingMapper.selectOwnedForUpdate(turn.tenantId(), turn.userId());
        if (setting == null || setting.getMemoryGeneration() == null) {
            throw writeFailed();
        }
        policy.requireEnabled(setting);
        long generation = setting.getMemoryGeneration();
        Long sourceSequence = messageMapper.selectOwnedUserMessageSequence(
                turn.tenantId(), turn.userId(), turn.conversationId(), turn.userMessageId());
        if (sourceSequence == null || sourceSequence < 1) {
            throw writeFailed();
        }

        UserMemoryEntity previous = memoryMapper.selectActiveByKeyForUpdate(
                turn.tenantId(), turn.userId(), generation, candidate.canonicalKey());
        long version = previous == null || previous.getVersion() == null
                ? 1L : previous.getVersion() + 1L;
        int superseded = memoryMapper.supersedeOwnedActive(
                turn.tenantId(), turn.userId(), generation, candidate.canonicalKey(), now);
        if (previous != null && (superseded != 1 || previous.getMemoryId() == null)) {
            throw writeFailed();
        }
        suppressionMapper.liftOwnedActive(
                turn.tenantId(), turn.userId(), generation, candidate.canonicalKey(), now);

        String memoryId = UUID.randomUUID().toString();
        UserMemoryEntity memory = new UserMemoryEntity();
        memory.setMemoryId(memoryId);
        memory.setTenantId(turn.tenantId());
        memory.setUserId(turn.userId());
        memory.setMemoryGeneration(generation);
        memory.setSourceType(MemorySourceType.USER_EXPLICIT.name());
        memory.setCategory(candidate.category().name());
        MemoryFactCandidate structuredFact = schemaRegistry.normalizeCandidate(
                structuredFact(candidate));
        MemorySchemaRegistry.SchemaResolution structured = schemaRegistry.resolve(structuredFact);
        memory.setSchemaVersion(2);
        memory.setMemoryType(structuredFact.memoryType().name());
        memory.setPredicateName(structuredFact.predicate());
        memory.setValueJson(structured.valueJson());
        memory.setStability(structuredFact.stability().name());
        memory.setVerificationMethod(candidate.semanticFact() == null
                ? "EXPLICIT_DETERMINISTIC" : "EXPLICIT_SEMANTIC");
        memory.setCanonicalKey(candidate.canonicalKey());
        memory.setContent(candidate.content());
        memory.setContentHash(MemoryHashing.sha256(candidate.content()));
        memory.setConfidence(new BigDecimal("1.0000"));
        memory.setVisibility(MemoryVisibility.VISIBLE.name());
        memory.setRetentionType(candidate.retentionType().name());
        memory.setStatus(MemoryStatus.ACTIVE.name());
        memory.setSourceConversationId(turn.conversationId());
        memory.setSourceMessageSequence(sourceSequence);
        memory.setEvidenceText(candidate.evidenceText());
        memory.setVersion(version);
        memory.setExpiresAt(candidate.retentionType() == MemoryRetentionType.PERMANENT
                ? null : now.plusDays(properties.explicitExpireDays()));
        memory.setCreatedAt(now);
        memory.setUpdatedAt(now);
        if (memoryMapper.insert(memory) != 1) {
            throw writeFailed();
        }

        if (previous != null) {
            insertOutbox(turn.tenantId(), turn.userId(), generation,
                    previous.getVersion(), previous.getMemoryId(), MemoryOutboxOperation.DELETE, now);
        }
        insertOutbox(turn.tenantId(), turn.userId(), generation,
                version, memoryId, MemoryOutboxOperation.UPSERT, now);
        return new SaveResult(memoryId, candidate.content());
    }

    private static MemoryFactCandidate structuredFact(ExplicitMemoryCandidate candidate) {
        if (candidate.semanticFact() != null) {
            return candidate.semanticFact();
        }
        String content = candidate.content();
        String value;
        MemoryType type;
        String predicate;
        switch (candidate.category()) {
            case PROFILE_PREFERRED_NAME -> {
                value = suffix(content, "用户希望被称为", "");
                type = MemoryType.PROFILE;
                predicate = "preferred_name";
            }
            case PREFERENCE_LANGUAGE -> {
                value = suffix(content, "用户偏好使用", "交流");
                type = MemoryType.COMMUNICATION_PREFERENCE;
                predicate = "answer_language";
            }
            case PREFERENCE_ANSWER_STYLE -> {
                value = suffix(content, "用户偏好", "回答");
                type = MemoryType.RESPONSE_PREFERENCE;
                predicate = "answer_style";
            }
            case WORK_COMMON_SCOPE -> {
                value = suffix(content, "用户常用工作范围是", "");
                type = MemoryType.WORK_CONTEXT;
                predicate = "common_scope";
            }
            default -> throw new IllegalArgumentException("MEMORY_CONTENT_REJECTED");
        }
        return new MemoryFactCandidate(type, predicate, value, value,
                candidate.evidenceText(), MemoryStability.STABLE, 1.0);
    }

    private static String suffix(String content, String prefix, String suffix) {
        if (content == null || !content.startsWith(prefix) || !content.endsWith(suffix)
                || content.length() <= prefix.length() + suffix.length()) {
            throw new IllegalArgumentException("MEMORY_CONTENT_REJECTED");
        }
        return content.substring(prefix.length(), content.length() - suffix.length()).strip();
    }

    private void insertOutbox(long tenantId, long userId, long generation, long version,
                              String memoryId, MemoryOutboxOperation operation, LocalDateTime now) {
        MemoryOutboxEntity outbox = new MemoryOutboxEntity();
        outbox.setEventId(UUID.randomUUID().toString());
        outbox.setMemoryId(memoryId);
        outbox.setTenantId(tenantId);
        outbox.setUserId(userId);
        outbox.setMemoryGeneration(generation);
        outbox.setMemoryVersion(version);
        outbox.setOperation(operation.name());
        outbox.setStatus(MemoryOutboxStatus.PENDING.name());
        outbox.setRetryCount(0);
        outbox.setNextRunAt(now);
        outbox.setCreatedAt(now);
        outbox.setUpdatedAt(now);
        if (outboxMapper.insert(outbox) != 1) {
            throw writeFailed();
        }
    }

    private static BusinessException writeFailed() {
        return new BusinessException(ApiErrorCode.MEMORY_WRITE_FAILED);
    }

    public record SaveResult(String memoryId, String content) {
    }
}
