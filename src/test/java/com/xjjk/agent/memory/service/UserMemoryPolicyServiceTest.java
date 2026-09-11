package com.xjjk.agent.memory.service;

import com.xjjk.agent.memory.persistence.entity.UserMemorySettingEntity;
import com.xjjk.agent.memory.persistence.mapper.UserMemorySettingMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserMemoryPolicyServiceTest {

    private final UserMemorySettingMapper mapper = mock(UserMemorySettingMapper.class);
    private final UserMemoryPolicyService service = new UserMemoryPolicyService(mapper);

    @Test
    void treatsMissingSettingAsEnabledAndStoredFalseAsDisabled() {
        when(mapper.selectOwned(1L, 2L)).thenReturn(null);
        assertThat(service.isMemoryEnabled(1L, 2L)).isTrue();

        UserMemorySettingEntity disabled = new UserMemorySettingEntity();
        disabled.setMemoryEnabled(false);
        when(mapper.selectOwned(1L, 2L)).thenReturn(disabled);
        assertThat(service.isMemoryEnabled(1L, 2L)).isFalse();
    }

    @Test
    void requireEnabledFailsClosedForFalseOrNull() {
        for (Boolean enabled : Arrays.asList(false, null)) {
            UserMemorySettingEntity setting = new UserMemorySettingEntity();
            setting.setMemoryEnabled(enabled);
            assertThatThrownBy(() -> service.requireEnabled(setting))
                    .isInstanceOf(UserMemoryDisabledException.class);
        }
    }
}
