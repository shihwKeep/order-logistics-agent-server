package com.xjjk.agent.memory.service;

import com.xjjk.agent.chat.domain.ChatTurnContext;
import com.xjjk.agent.chat.persistence.mapper.AgentMessageMapper;
import com.xjjk.agent.memory.config.UserMemoryProperties;
import com.xjjk.agent.memory.persistence.entity.UserMemorySettingEntity;
import com.xjjk.agent.memory.persistence.mapper.MemoryExtractionTaskMapper;
import com.xjjk.agent.memory.persistence.mapper.UserMemorySettingMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/** 在聊天成功收尾事务中幂等登记隐式记忆抽取任务。 */
@Service
public class ImplicitMemoryTaskScheduler {

    private final UserMemorySettingMapper settingMapper;
    private final AgentMessageMapper messageMapper;
    private final MemoryExtractionTaskMapper taskMapper;
    private final UserMemoryProperties properties;
    private final Clock clock;

    @Autowired
    public ImplicitMemoryTaskScheduler(
            UserMemorySettingMapper settingMapper,
            AgentMessageMapper messageMapper,
            MemoryExtractionTaskMapper taskMapper,
            UserMemoryProperties properties
    ) {
        this(settingMapper, messageMapper, taskMapper, properties, Clock.systemUTC());
    }

    ImplicitMemoryTaskScheduler(
            UserMemorySettingMapper settingMapper,
            AgentMessageMapper messageMapper,
            MemoryExtractionTaskMapper taskMapper,
            UserMemoryProperties properties,
            Clock clock
    ) {
        this.settingMapper = Objects.requireNonNull(settingMapper, "settingMapper");
        this.messageMapper = Objects.requireNonNull(messageMapper, "messageMapper");
        this.taskMapper = Objects.requireNonNull(taskMapper, "taskMapper");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void request(ChatTurnContext turn) {
        Objects.requireNonNull(turn, "turn");
        if (!properties.enabled()) {
            return;
        }
        LocalDateTime now = LocalDateTime.ofInstant(
                clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
        int insertedSetting = settingMapper.insertIfAbsent(
                turn.tenantId(), turn.userId(), true,
                properties.autoExtractDefaultEnabled(), now);
        if (insertedSetting < 0 || insertedSetting > 1) {
            throw new IllegalStateException("隐式记忆设置初始化失败");
        }
        UserMemorySettingEntity setting = settingMapper.selectOwnedForUpdate(
                turn.tenantId(), turn.userId());
        if (setting == null || setting.getMemoryGeneration() == null) {
            throw new IllegalStateException("隐式记忆设置不存在");
        }
        if (!Boolean.TRUE.equals(setting.getMemoryEnabled())
                || !Boolean.TRUE.equals(setting.getAutoExtractEnabled())) {
            return;
        }
        Long sourceSequence = messageMapper.selectOwnedUserMessageSequence(
                turn.tenantId(), turn.userId(), turn.conversationId(), turn.userMessageId());
        if (sourceSequence == null || sourceSequence < 1) {
            throw new IllegalStateException("隐式记忆来源消息不存在");
        }
        int inserted = taskMapper.insertRequested(
                turn.tenantId(), turn.userId(), turn.conversationId(), turn.requestId(),
                turn.userMessageId(), sourceSequence, setting.getMemoryGeneration(),
                UUID.randomUUID().toString(), now);
        if (inserted < 0 || inserted > 1) {
            throw new IllegalStateException("隐式记忆任务登记失败");
        }
    }
}
