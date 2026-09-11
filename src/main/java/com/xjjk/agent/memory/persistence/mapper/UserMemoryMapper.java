package com.xjjk.agent.memory.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xjjk.agent.memory.persistence.entity.UserMemoryEntity;
import com.xjjk.agent.memory.persistence.projection.UserMemoryListRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface UserMemoryMapper extends BaseMapper<UserMemoryEntity> {

    @Select("""
        SELECT *
        FROM agent_user_memory
        WHERE tenant_id = #{tenantId}
          AND user_id = #{userId}
          AND memory_generation = #{generation}
          AND memory_id = #{memoryId}
          AND source_type = 'USER_EXPLICIT'
          AND visibility = 'VISIBLE'
          AND status = 'ACTIVE'
        LIMIT 1
        FOR UPDATE
        """)
    UserMemoryEntity selectOwnedVisibleExplicitForUpdate(
            @Param("tenantId") long tenantId,
            @Param("userId") long userId,
            @Param("generation") long generation,
            @Param("memoryId") String memoryId);

    @Select("""
        SELECT *
        FROM agent_user_memory
        WHERE tenant_id = #{tenantId}
          AND user_id = #{userId}
          AND memory_generation = #{generation}
          AND source_type = 'USER_EXPLICIT'
          AND visibility = 'VISIBLE'
          AND status = 'ACTIVE'
        ORDER BY id
        FOR UPDATE
        """)
    List<UserMemoryEntity> selectAllOwnedVisibleExplicitForUpdate(
            @Param("tenantId") long tenantId,
            @Param("userId") long userId,
            @Param("generation") long generation);

    @Select("""
        SELECT *
        FROM agent_user_memory
        WHERE tenant_id = #{tenantId}
          AND user_id = #{userId}
          AND memory_generation = #{generation}
          AND canonical_key = #{canonicalKey}
          AND status = 'ACTIVE'
        ORDER BY version DESC, id DESC
        LIMIT 1
        FOR UPDATE
        """)
    UserMemoryEntity selectActiveByKeyForUpdate(
            @Param("tenantId") long tenantId,
            @Param("userId") long userId,
            @Param("generation") long generation,
            @Param("canonicalKey") String canonicalKey);

    @Select("""
        SELECT id,
               memory_id AS memoryId,
               category,
               content,
               retention_type AS retentionType,
               version,
               updated_at AS updatedAt
        FROM agent_user_memory
        WHERE tenant_id = #{tenantId}
          AND user_id = #{userId}
          AND memory_generation = #{generation}
          AND source_type = 'USER_EXPLICIT'
          AND visibility = 'VISIBLE'
          AND status = 'ACTIVE'
          AND (
                #{beforeUpdatedAt,jdbcType=TIMESTAMP} IS NULL
                OR updated_at < #{beforeUpdatedAt,jdbcType=TIMESTAMP}
                OR (
                    updated_at = #{beforeUpdatedAt,jdbcType=TIMESTAMP}
                    AND id < #{beforeId,jdbcType=BIGINT}
                )
          )
        ORDER BY updated_at DESC, id DESC
        LIMIT #{limit}
        """)
    List<UserMemoryListRow> selectVisiblePage(
            @Param("tenantId") long tenantId,
            @Param("userId") long userId,
            @Param("generation") long generation,
            @Param("beforeUpdatedAt") LocalDateTime beforeUpdatedAt,
            @Param("beforeId") Long beforeId,
            @Param("limit") int limit);

    @Update("""
        UPDATE agent_user_memory
        SET status = 'SUPERSEDED', updated_at = #{updatedAt}
        WHERE tenant_id = #{tenantId}
          AND user_id = #{userId}
          AND memory_generation = #{generation}
          AND canonical_key = #{canonicalKey}
          AND status = 'ACTIVE'
        """)
    int supersedeOwnedActive(
            @Param("tenantId") long tenantId,
            @Param("userId") long userId,
            @Param("generation") long generation,
            @Param("canonicalKey") String canonicalKey,
            @Param("updatedAt") LocalDateTime updatedAt);

    @Update("""
        UPDATE agent_user_memory
        SET status = 'DELETED', updated_at = #{updatedAt}
        WHERE tenant_id = #{tenantId}
          AND user_id = #{userId}
          AND memory_generation = #{generation}
          AND memory_id = #{memoryId}
          AND source_type = 'USER_EXPLICIT'
          AND visibility = 'VISIBLE'
          AND status = 'ACTIVE'
        """)
    int softDeleteOwned(
            @Param("tenantId") long tenantId,
            @Param("userId") long userId,
            @Param("generation") long generation,
            @Param("memoryId") String memoryId,
            @Param("updatedAt") LocalDateTime updatedAt);

    @Update("""
        UPDATE agent_user_memory
        SET status = 'DELETED', updated_at = #{updatedAt}
        WHERE tenant_id = #{tenantId}
          AND user_id = #{userId}
          AND memory_generation = #{generation}
          AND source_type = 'USER_EXPLICIT'
          AND visibility = 'VISIBLE'
          AND status = 'ACTIVE'
        """)
    int clearOwnedExplicit(
            @Param("tenantId") long tenantId,
            @Param("userId") long userId,
            @Param("generation") long generation,
            @Param("updatedAt") LocalDateTime updatedAt);

    @Update("""
        UPDATE agent_user_memory
        SET status = 'DELETED', updated_at = #{updatedAt}
        WHERE tenant_id = #{tenantId}
          AND user_id = #{userId}
          AND memory_generation = #{generation}
          AND status = 'ACTIVE'
        """)
    int clearOwnedGeneration(
            @Param("tenantId") long tenantId,
            @Param("userId") long userId,
            @Param("generation") long generation,
            @Param("updatedAt") LocalDateTime updatedAt);
}
