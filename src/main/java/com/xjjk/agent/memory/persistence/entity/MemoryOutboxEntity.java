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
@TableName("agent_memory_outbox")
public class MemoryOutboxEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("event_id")
    private String eventId;

    @TableField("memory_id")
    private String memoryId;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("user_id")
    private Long userId;

    @TableField("memory_generation")
    private Long memoryGeneration;

    @TableField("memory_version")
    private Long memoryVersion;

    @TableField("operation")
    private String operation;

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

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
