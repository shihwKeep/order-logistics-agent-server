package com.xjjk.agent.memory.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("agent_memory_extraction_task")
public class MemoryExtractionTaskEntity {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    @TableField("task_id")
    private String taskId;
    @TableField("tenant_id")
    private Long tenantId;
    @TableField("user_id")
    private Long userId;
    @TableField("conversation_id")
    private String conversationId;
    @TableField("request_id")
    private String requestId;
    @TableField("user_message_id")
    private String userMessageId;
    @TableField("user_message_sequence")
    private Long userMessageSequence;
    @TableField("memory_generation")
    private Long memoryGeneration;
    @TableField("status")
    private String status;
    @TableField("retry_count")
    private Integer retryCount;
    @TableField("next_run_at")
    private LocalDateTime nextRunAt;
    @TableField("lease_token")
    private String leaseToken;
    @TableField("locked_by")
    private String lockedBy;
    @TableField("locked_until")
    private LocalDateTime lockedUntil;
    @TableField("last_error_code")
    private String lastErrorCode;
    @TableField("result_code")
    private String resultCode;
    @TableField("model_candidate_count")
    private Integer modelCandidateCount;
    @TableField("accepted_candidate_count")
    private Integer acceptedCandidateCount;
    @TableField("saved_memory_count")
    private Integer savedMemoryCount;
    @TableField("created_at")
    private LocalDateTime createdAt;
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
