package com.xjjk.agent.memory.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xjjk.agent.memory.persistence.entity.MemoryExtractionTaskEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

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
}
