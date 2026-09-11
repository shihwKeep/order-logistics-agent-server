package com.xjjk.agent.memory.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xjjk.agent.memory.persistence.entity.MemorySuppressionEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface MemorySuppressionMapper extends BaseMapper<MemorySuppressionEntity> {

    @Insert("""
        INSERT INTO agent_memory_suppression (
            suppression_id, tenant_id, user_id, memory_generation, canonical_key,
            content_hash, status, created_at, updated_at
        ) VALUES (
            #{entity.suppressionId}, #{entity.tenantId}, #{entity.userId},
            #{entity.memoryGeneration}, #{entity.canonicalKey}, #{entity.contentHash},
            #{entity.status}, #{entity.createdAt}, #{entity.updatedAt}
        )
        """)
    int insertOwned(@Param("entity") MemorySuppressionEntity entity);

    @Update("""
        UPDATE agent_memory_suppression
        SET status = 'LIFTED', updated_at = #{updatedAt}
        WHERE tenant_id = #{tenantId}
          AND user_id = #{userId}
          AND memory_generation = #{generation}
          AND canonical_key = #{canonicalKey}
          AND status = 'ACTIVE'
        """)
    int liftOwnedActive(
            @Param("tenantId") long tenantId,
            @Param("userId") long userId,
            @Param("generation") long generation,
            @Param("canonicalKey") String canonicalKey,
            @Param("updatedAt") LocalDateTime updatedAt);
}
