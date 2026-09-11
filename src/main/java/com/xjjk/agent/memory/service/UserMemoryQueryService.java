package com.xjjk.agent.memory.service;

import com.xjjk.agent.common.api.ApiErrorCode;
import com.xjjk.agent.common.exception.BusinessException;
import com.xjjk.agent.identity.domain.AgentIdentity;
import com.xjjk.agent.memory.api.dto.UserMemoryPageResponse;
import com.xjjk.agent.memory.api.dto.UserMemoryResponse;
import com.xjjk.agent.memory.api.dto.UserMemorySettingResponse;
import com.xjjk.agent.memory.config.UserMemoryProperties;
import com.xjjk.agent.memory.persistence.entity.UserMemorySettingEntity;
import com.xjjk.agent.memory.persistence.mapper.UserMemoryMapper;
import com.xjjk.agent.memory.persistence.mapper.UserMemorySettingMapper;
import com.xjjk.agent.memory.persistence.projection.UserMemoryListRow;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

@Service
public class UserMemoryQueryService {

    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Shanghai");
    private final UserMemoryMapper memoryMapper;
    private final UserMemorySettingMapper settingMapper;
    private final UserMemoryPageCursorCodec cursorCodec;
    private final UserMemoryProperties properties;
    private final Clock clock;

    @Autowired
    public UserMemoryQueryService(
            UserMemoryMapper memoryMapper,
            UserMemorySettingMapper settingMapper,
            UserMemoryPageCursorCodec cursorCodec,
            UserMemoryProperties properties
    ) {
        this(memoryMapper, settingMapper, cursorCodec, properties, Clock.systemUTC());
    }

    UserMemoryQueryService(
            UserMemoryMapper memoryMapper,
            UserMemorySettingMapper settingMapper,
            UserMemoryPageCursorCodec cursorCodec,
            UserMemoryProperties properties,
            Clock clock
    ) {
        this.memoryMapper = Objects.requireNonNull(memoryMapper);
        this.settingMapper = Objects.requireNonNull(settingMapper);
        this.cursorCodec = Objects.requireNonNull(cursorCodec);
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
    }

    public UserMemoryPageResponse list(AgentIdentity identity, String cursor, int pageSize) {
        if (pageSize < 1 || pageSize > properties.pageSizeMax()) {
            throw new BusinessException(ApiErrorCode.VALIDATION_ERROR);
        }
        UserMemorySettingEntity setting = settingMapper.selectOwned(identity.tenantId(), identity.userId());
        long generation = setting == null ? 1L : setting.getMemoryGeneration();
        UserMemoryPageCursor boundary = cursor == null || cursor.isBlank()
                ? null : cursorCodec.decode(cursor, identity.tenantId(), identity.userId(), generation);
        List<UserMemoryListRow> rows = memoryMapper.selectVisiblePage(
                identity.tenantId(), identity.userId(), generation,
                boundary == null ? null : boundary.updatedAt(),
                boundary == null ? null : boundary.id(), pageSize + 1);
        boolean hasMore = rows.size() > pageSize;
        List<UserMemoryListRow> visible = hasMore ? rows.subList(0, pageSize) : rows;
        String nextCursor = hasMore
                ? cursorCodec.encode(new UserMemoryPageCursor(
                        visible.get(visible.size() - 1).getUpdatedAt(),
                        visible.get(visible.size() - 1).getId(),
                        identity.tenantId(), identity.userId(), generation))
                : null;
        return new UserMemoryPageResponse(visible.stream().map(this::toResponse).toList(),
                nextCursor, hasMore);
    }

    public UserMemorySettingResponse getSetting(AgentIdentity identity) {
        UserMemorySettingEntity setting = settingMapper.selectOwned(identity.tenantId(), identity.userId());
        return new UserMemorySettingResponse(setting == null
                ? properties.autoExtractDefaultEnabled()
                : Boolean.TRUE.equals(setting.getAutoExtractEnabled()));
    }

    @Transactional
    public UserMemorySettingResponse updateSetting(AgentIdentity identity, boolean enabled) {
        LocalDateTime now = LocalDateTime.ofInstant(
                clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
        settingMapper.insertIfAbsent(identity.tenantId(), identity.userId(),
                true, properties.autoExtractDefaultEnabled(), now);
        UserMemorySettingEntity setting = settingMapper.selectOwnedForUpdate(
                identity.tenantId(), identity.userId());
        if (setting == null || settingMapper.updateAutoExtractEnabled(
                identity.tenantId(), identity.userId(), enabled, now) != 1) {
            throw new BusinessException(ApiErrorCode.MEMORY_WRITE_FAILED);
        }
        return new UserMemorySettingResponse(enabled);
    }

    private UserMemoryResponse toResponse(UserMemoryListRow row) {
        return new UserMemoryResponse(row.getMemoryId(), row.getCategory(), row.getContent(),
                row.getRetentionType(), row.getVersion(),
                row.getUpdatedAt().atZone(ZoneOffset.UTC)
                        .withZoneSameInstant(DISPLAY_ZONE).toOffsetDateTime());
    }
}
