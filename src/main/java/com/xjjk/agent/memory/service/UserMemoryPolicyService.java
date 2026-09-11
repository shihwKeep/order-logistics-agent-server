package com.xjjk.agent.memory.service;

import com.xjjk.agent.memory.persistence.entity.UserMemorySettingEntity;
import com.xjjk.agent.memory.persistence.mapper.UserMemorySettingMapper;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
public class UserMemoryPolicyService {

    private final UserMemorySettingMapper settingMapper;

    public UserMemoryPolicyService(UserMemorySettingMapper settingMapper) {
        this.settingMapper = Objects.requireNonNull(settingMapper, "settingMapper");
    }

    public boolean isMemoryEnabled(long tenantId, long userId) {
        UserMemorySettingEntity setting = settingMapper.selectOwned(tenantId, userId);
        return setting == null || Boolean.TRUE.equals(setting.getMemoryEnabled());
    }

    public void requireEnabled(UserMemorySettingEntity setting) {
        if (setting == null || !Boolean.TRUE.equals(setting.getMemoryEnabled())) {
            throw new UserMemoryDisabledException();
        }
    }
}
