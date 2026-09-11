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
@TableName("agent_memory_suppression")
public class MemorySuppressionEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("suppression_id")
    private String suppressionId;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("user_id")
    private Long userId;

    @TableField("memory_generation")
    private Long memoryGeneration;

    @TableField("canonical_key")
    private String canonicalKey;

    @TableField("content_hash")
    private String contentHash;

    @TableField("status")
    private String status;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
