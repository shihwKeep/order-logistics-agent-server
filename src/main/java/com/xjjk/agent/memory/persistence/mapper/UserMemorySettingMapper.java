package com.xjjk.agent.memory.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xjjk.agent.memory.persistence.entity.UserMemorySettingEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface UserMemorySettingMapper extends BaseMapper<UserMemorySettingEntity> {

    @Insert("""
        INSERT IGNORE INTO agent_user_memory_setting (
            tenant_id, user_id, memory_generation, memory_enabled,
            auto_extract_enabled, created_at, updated_at
        ) VALUES (
            #{tenantId}, #{userId}, 1, #{memoryEnabled},
            #{autoExtractEnabled}, #{now}, #{now}
        )
        """)
    int insertIfAbsent(
            @Param("tenantId") long tenantId,
            @Param("userId") long userId,
            @Param("memoryEnabled") boolean memoryEnabled,
            @Param("autoExtractEnabled") boolean autoExtractEnabled,
            @Param("now") LocalDateTime now);

    @Select("""
        SELECT * FROM agent_user_memory_setting
        WHERE tenant_id = #{tenantId} AND user_id = #{userId}
        """)
    UserMemorySettingEntity selectOwned(
            @Param("tenantId") long tenantId,
            @Param("userId") long userId);

    @Select("""
        SELECT * FROM agent_user_memory_setting
        WHERE tenant_id = #{tenantId} AND user_id = #{userId}
        FOR UPDATE
        """)
    UserMemorySettingEntity selectOwnedForUpdate(
            @Param("tenantId") long tenantId,
            @Param("userId") long userId);

    @Update("""
        UPDATE agent_user_memory_setting
        SET auto_extract_enabled = #{enabled}, updated_at = #{updatedAt}
        WHERE tenant_id = #{tenantId} AND user_id = #{userId}
        """)
    int updateAutoExtractEnabled(
            @Param("tenantId") long tenantId,
            @Param("userId") long userId,
            @Param("enabled") boolean enabled,
            @Param("updatedAt") LocalDateTime updatedAt);

    @Update("""
        UPDATE agent_user_memory_setting
        SET memory_generation = memory_generation + 1, updated_at = #{updatedAt}
        WHERE tenant_id = #{tenantId}
          AND user_id = #{userId}
          AND memory_generation = #{expectedGeneration}
        """)
    int compareAndIncrementGeneration(
            @Param("tenantId") long tenantId,
            @Param("userId") long userId,
            @Param("expectedGeneration") long expectedGeneration,
            @Param("updatedAt") LocalDateTime updatedAt);
}
