package com.xjjk.agent.memory.service;

import com.xjjk.agent.common.api.ApiErrorCode;
import com.xjjk.agent.common.exception.BusinessException;
import com.xjjk.agent.identity.domain.AgentIdentity;
import com.xjjk.agent.memory.config.UserMemoryProperties;
import com.xjjk.agent.memory.persistence.entity.UserMemorySettingEntity;
import com.xjjk.agent.memory.persistence.mapper.UserMemoryMapper;
import com.xjjk.agent.memory.persistence.mapper.UserMemorySettingMapper;
import com.xjjk.agent.memory.persistence.projection.UserMemoryListRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserMemoryQueryServiceTest {

    private final UserMemoryMapper memoryMapper = mock(UserMemoryMapper.class);
    private final UserMemorySettingMapper settingMapper = mock(UserMemorySettingMapper.class);
    private final AgentIdentity identity = new AgentIdentity(2L, "account", "name", 3L, 1L);
    private UserMemoryQueryService service;

    @BeforeEach
    void setUp() {
        service = new UserMemoryQueryService(memoryMapper, settingMapper,
                new UserMemoryPageCursorCodec(), properties());
    }

    @Test
    void usesLimitPlusOneAndBuildsCursorFromLastReturnedRow() {
        UserMemorySettingEntity setting = setting(7L, true);
        when(settingMapper.selectOwned(1L, 2L)).thenReturn(setting);
        List<UserMemoryListRow> rows = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            UserMemoryListRow row = new UserMemoryListRow();
            row.setId(100L - i);
            row.setMemoryId("memory-" + i);
            row.setCategory("PREFERENCE_ANSWER_STYLE");
            row.setContent("content-" + i);
            row.setRetentionType("NORMAL");
            row.setVersion(1L);
            row.setUpdatedAt(LocalDateTime.parse("2026-09-11T08:00:00").minusSeconds(i));
            rows.add(row);
        }
        when(memoryMapper.selectVisiblePage(1L, 2L, 7L, null, null, 11)).thenReturn(rows);

        var page = service.list(identity, null, 10);

        assertThat(page.items()).hasSize(10);
        assertThat(page.hasMore()).isTrue();
        UserMemoryPageCursor cursor = new UserMemoryPageCursorCodec().decode(page.nextCursor());
        assertThat(cursor.id()).isEqualTo(rows.get(9).getId());
        assertThat(cursor.updatedAt()).isEqualTo(rows.get(9).getUpdatedAt());
    }

    @Test
    void rejectsInvalidPageSizesBeforeDatabaseAccess() {
        for (int invalid : List.of(0, 51)) {
            assertThatThrownBy(() -> service.list(identity, null, invalid))
                    .isInstanceOfSatisfying(BusinessException.class,
                            error -> assertThat(error.errorCode()).isEqualTo(ApiErrorCode.VALIDATION_ERROR));
        }
        verify(settingMapper, never()).selectOwned(1L, 2L);
    }

    @Test
    void returnsConfiguredSettingDefaultWithoutCreatingRow() {
        when(settingMapper.selectOwned(1L, 2L)).thenReturn(null);
        assertThat(service.getSetting(identity).autoExtractEnabled()).isTrue();
        verify(settingMapper, never()).insertIfAbsent(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.any());
    }

    private UserMemorySettingEntity setting(long generation, boolean enabled) {
        UserMemorySettingEntity entity = new UserMemorySettingEntity();
        entity.setMemoryGeneration(generation);
        entity.setAutoExtractEnabled(enabled);
        return entity;
    }

    private UserMemoryProperties properties() {
        return new UserMemoryProperties(true, true, 256, 512, 512, 50, 365,
                "memory-test-v1", "qwen-plus", 0.1, Duration.ofSeconds(1), 1, 10);
    }
}
