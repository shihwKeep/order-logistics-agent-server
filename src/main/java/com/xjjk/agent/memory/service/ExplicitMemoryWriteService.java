package com.xjjk.agent.memory.service;

import com.xjjk.agent.chat.domain.ChatTurnContext;
import com.xjjk.agent.chat.persistence.mapper.AgentMessageMapper;
import com.xjjk.agent.common.api.ApiErrorCode;
import com.xjjk.agent.common.exception.BusinessException;
import com.xjjk.agent.memory.config.UserMemoryProperties;
import com.xjjk.agent.memory.domain.ExplicitMemoryCandidate;
import com.xjjk.agent.memory.domain.MemoryOutboxOperation;
import com.xjjk.agent.memory.domain.MemoryOutboxStatus;
import com.xjjk.agent.memory.domain.MemoryRetentionType;
import com.xjjk.agent.memory.domain.MemorySourceType;
import com.xjjk.agent.memory.domain.MemoryStatus;
import com.xjjk.agent.memory.domain.MemoryVisibility;
import com.xjjk.agent.memory.persistence.entity.MemoryOutboxEntity;
import com.xjjk.agent.memory.persistence.entity.UserMemoryEntity;
import com.xjjk.agent.memory.persistence.entity.UserMemorySettingEntity;
import com.xjjk.agent.memory.persistence.mapper.MemoryOutboxMapper;
import com.xjjk.agent.memory.persistence.mapper.MemorySuppressionMapper;
import com.xjjk.agent.memory.persistence.mapper.UserMemoryMapper;
import com.xjjk.agent.memory.persistence.mapper.UserMemorySettingMapper;
import org.springframework.stereotype.Service;
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
    private final Clock clock;

    public ExplicitMemoryWriteService(
            UserMemorySettingMapper settingMapper,
            UserMemoryMapper memoryMapper,
            MemorySuppressionMapper suppressionMapper,
            MemoryOutboxMapper outboxMapper,
            AgentMessageMapper messageMapper,
            UserMemoryProperties properties
    ) {
        this(settingMapper, memoryMapper, suppressionMapper, outboxMapper,
                messageMapper, properties, Clock.systemUTC());
    }

    ExplicitMemoryWriteService(
            UserMemorySettingMapper settingMapper,
            UserMemoryMapper memoryMapper,
            MemorySuppressionMapper suppressionMapper,
            MemoryOutboxMapper outboxMapper,
            AgentMessageMapper messageMapper,
            UserMemoryProperties properties,
            Clock clock
    ) {
        this.settingMapper = Objects.requireNonNull(settingMapper, "settingMapper");
        this.memoryMapper = Objects.requireNonNull(memoryMapper, "memoryMapper");
        this.suppressionMapper = Objects.requireNonNull(suppressionMapper, "suppressionMapper");
        this.outboxMapper = Objects.requireNonNull(outboxMapper, "outboxMapper");
        this.messageMapper = Objects.requireNonNull(messageMapper, "messageMapper");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Transactional
    public SaveResult save(ChatTurnContext turn, ExplicitMemoryCandidate candidate) {
        Objects.requireNonNull(turn, "turn");
        Objects.requireNonNull(candidate, "candidate");
        LocalDateTime now = LocalDateTime.ofInstant(
                clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);

        int settingInsert = settingMapper.insertIfAbsent(
                turn.tenantId(), turn.userId(), properties.autoExtractDefaultEnabled(), now);
        if (settingInsert < 0 || settingInsert > 1) {
            throw writeFailed();
        }
        UserMemorySettingEntity setting = settingMapper.selectOwnedForUpdate(turn.tenantId(), turn.userId());
        if (setting == null || setting.getMemoryGeneration() == null) {
            throw writeFailed();
        }
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
        memoryMapper.supersedeOwnedActive(
                turn.tenantId(), turn.userId(), generation, candidate.canonicalKey(), now);
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

        MemoryOutboxEntity outbox = new MemoryOutboxEntity();
        outbox.setEventId(UUID.randomUUID().toString());
        outbox.setMemoryId(memoryId);
        outbox.setTenantId(turn.tenantId());
        outbox.setUserId(turn.userId());
        outbox.setMemoryGeneration(generation);
        outbox.setMemoryVersion(version);
        outbox.setOperation(MemoryOutboxOperation.UPSERT.name());
        outbox.setStatus(MemoryOutboxStatus.PENDING.name());
        outbox.setRetryCount(0);
        outbox.setNextRunAt(now);
        outbox.setCreatedAt(now);
        outbox.setUpdatedAt(now);
        if (outboxMapper.insert(outbox) != 1) {
            throw writeFailed();
        }
        return new SaveResult(memoryId, candidate.content());
    }

    private static BusinessException writeFailed() {
        return new BusinessException(ApiErrorCode.MEMORY_WRITE_FAILED);
    }

    public record SaveResult(String memoryId, String content) {
    }
}
