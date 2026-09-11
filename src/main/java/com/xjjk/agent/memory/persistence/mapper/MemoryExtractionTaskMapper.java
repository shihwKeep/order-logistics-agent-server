package com.xjjk.agent.memory.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xjjk.agent.memory.persistence.entity.MemoryExtractionTaskEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface MemoryExtractionTaskMapper extends BaseMapper<MemoryExtractionTaskEntity> {

    @Insert("""
        INSERT IGNORE INTO agent_memory_extraction_task (
            task_id, tenant_id, user_id, conversation_id, request_id,
            user_message_id, user_message_sequence, memory_generation,
            status, retry_count, next_run_at, created_at, updated_at
        ) VALUES (
            #{taskId}, #{tenantId}, #{userId}, #{conversationId}, #{requestId},
            #{userMessageId}, #{userMessageSequence}, #{memoryGeneration},
            'PENDING', 0, #{now}, #{now}, #{now}
        )
        """)
    int insertRequested(
            @Param("tenantId") long tenantId,
            @Param("userId") long userId,
            @Param("conversationId") String conversationId,
            @Param("requestId") String requestId,
            @Param("userMessageId") String userMessageId,
            @Param("userMessageSequence") long userMessageSequence,
            @Param("memoryGeneration") long memoryGeneration,
            @Param("taskId") String taskId,
            @Param("now") LocalDateTime now);

    @Select("""
        SELECT * FROM agent_memory_extraction_task
        WHERE id = #{id}
          AND status = 'PROCESSING'
          AND lease_token = #{leaseToken}
          AND locked_by = #{lockedBy}
        FOR UPDATE
        """)
    MemoryExtractionTaskEntity selectLeaseForUpdate(
            @Param("id") long id,
            @Param("leaseToken") String leaseToken,
            @Param("lockedBy") String lockedBy);

    @Update("""
        UPDATE agent_memory_extraction_task
        SET status = 'DONE', lease_token = NULL, locked_by = NULL,
            locked_until = NULL, last_error_code = NULL, updated_at = #{now}
        WHERE id = #{id} AND status = 'PROCESSING'
          AND lease_token = #{leaseToken} AND locked_by = #{lockedBy}
        """)
    int completeLease(
            @Param("id") long id,
            @Param("leaseToken") String leaseToken,
            @Param("lockedBy") String lockedBy,
            @Param("now") LocalDateTime now);

    @Update("""
        UPDATE agent_memory_extraction_task
        SET status = 'CANCELLED', lease_token = NULL, locked_by = NULL,
            locked_until = NULL, last_error_code = #{errorCode}, updated_at = #{now}
        WHERE id = #{id} AND status = 'PROCESSING'
          AND lease_token = #{leaseToken} AND locked_by = #{lockedBy}
        """)
    int cancelLease(
            @Param("id") long id,
            @Param("leaseToken") String leaseToken,
            @Param("lockedBy") String lockedBy,
            @Param("errorCode") String errorCode,
            @Param("now") LocalDateTime now);
}
