package com.xjjk.agent.memory.service;

import com.xjjk.agent.chat.domain.ChatTurnContext;
import com.xjjk.agent.chat.persistence.mapper.AgentMessageMapper;
import com.xjjk.agent.memory.config.UserMemoryProperties;
import com.xjjk.agent.memory.persistence.entity.UserMemorySettingEntity;
import com.xjjk.agent.memory.persistence.mapper.MemoryExtractionTaskMapper;
import com.xjjk.agent.memory.persistence.mapper.UserMemorySettingMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImplicitMemoryTaskSchedulerTest {

    private final UserMemorySettingMapper settingMapper = mock(UserMemorySettingMapper.class);
    private final AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
    private final MemoryExtractionTaskMapper taskMapper = mock(MemoryExtractionTaskMapper.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-11T13:30:00Z"), ZoneOffset.UTC);
    private final ChatTurnContext turn = new ChatTurnContext(
            1L, 2L, "conversation-1", "request-1", "user-1", "assistant-1", "prompt-v1");

    @Test
    void schedulesOwnedSourceWithLockedGeneration() {
        UserMemorySettingEntity setting = setting(true, true, 4L);
        when(settingMapper.insertIfAbsent(eq(1L), eq(2L), eq(true), eq(true), any())).thenReturn(0);
        when(settingMapper.selectOwnedForUpdate(1L, 2L)).thenReturn(setting);
        when(messageMapper.selectOwnedUserMessageSequence(1L, 2L, "conversation-1", "user-1"))
                .thenReturn(17L);
        when(taskMapper.insertRequested(
                eq(1L), eq(2L), eq("conversation-1"), eq("request-1"), eq("user-1"),
                eq(17L), eq(4L), anyString(), any())).thenReturn(1);

        scheduler(enabledProperties()).request(turn);

        verify(taskMapper).insertRequested(
                eq(1L), eq(2L), eq("conversation-1"), eq("request-1"), eq("user-1"),
                eq(17L), eq(4L),
                anyString(), any());
    }

    @Test
    void skipsWhenMasterOrAutoExtractionIsDisabled() {
        when(settingMapper.insertIfAbsent(eq(1L), eq(2L), eq(true), eq(true), any())).thenReturn(0);
        when(settingMapper.selectOwnedForUpdate(1L, 2L)).thenReturn(setting(false, true, 4L));

        scheduler(enabledProperties()).request(turn);

        verify(messageMapper, never()).selectOwnedUserMessageSequence(1L, 2L, "conversation-1", "user-1");
        verify(taskMapper, never()).insertRequested(
                anyLong(), anyLong(), anyString(), anyString(), anyString(),
                anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void treatsDuplicateRequestAsIdempotentButRejectsMissingSource() {
        when(settingMapper.insertIfAbsent(eq(1L), eq(2L), eq(true), eq(true), any())).thenReturn(0);
        when(settingMapper.selectOwnedForUpdate(1L, 2L)).thenReturn(setting(true, true, 4L));
        when(messageMapper.selectOwnedUserMessageSequence(1L, 2L, "conversation-1", "user-1"))
                .thenReturn(17L);
        when(taskMapper.insertRequested(
                eq(1L), eq(2L), eq("conversation-1"), eq("request-1"), eq("user-1"),
                eq(17L), eq(4L), anyString(), any())).thenReturn(0);

        assertThatCode(() -> scheduler(enabledProperties()).request(turn)).doesNotThrowAnyException();

        when(messageMapper.selectOwnedUserMessageSequence(1L, 2L, "conversation-1", "user-1"))
                .thenReturn(null);
        assertThatThrownBy(() -> scheduler(enabledProperties()).request(turn))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void globalDisableDoesNotTouchPersistence() {
        scheduler(disabledProperties()).request(turn);

        verify(settingMapper, never()).selectOwnedForUpdate(1L, 2L);
    }

    private ImplicitMemoryTaskScheduler scheduler(UserMemoryProperties properties) {
        return new ImplicitMemoryTaskScheduler(
                settingMapper, messageMapper, taskMapper, properties, clock);
    }

    private static UserMemorySettingEntity setting(boolean memory, boolean auto, long generation) {
        UserMemorySettingEntity setting = new UserMemorySettingEntity();
        setting.setMemoryEnabled(memory);
        setting.setAutoExtractEnabled(auto);
        setting.setMemoryGeneration(generation);
        return setting;
    }

    private static UserMemoryProperties enabledProperties() {
        return properties(true);
    }

    private static UserMemoryProperties disabledProperties() {
        return properties(false);
    }

    private static UserMemoryProperties properties(boolean enabled) {
        return new UserMemoryProperties(enabled, true, 256, 512, 512, 50, 365,
                "memory-explicit-v1", "qwen-plus", 0.1,
                Duration.ofSeconds(10), 1, 10);
    }
}
