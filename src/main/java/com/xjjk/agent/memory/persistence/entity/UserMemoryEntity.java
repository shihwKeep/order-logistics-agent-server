package com.xjjk.agent.memory.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@TableName("agent_user_memory")
public class UserMemoryEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("memory_id")
    private String memoryId;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("user_id")
    private Long userId;

    @TableField("memory_generation")
    private Long memoryGeneration;

    @TableField("source_type")
    private String sourceType;

    @TableField("category")
    private String category;

    @TableField("schema_version")
    private Integer schemaVersion;

    @TableField("memory_type")
    private String memoryType;

    @TableField("predicate_name")
    private String predicateName;

    @TableField("value_json")
    private String valueJson;

    @TableField("stability")
    private String stability;

    @TableField("verification_method")
    private String verificationMethod;

    @TableField("canonical_key")
    private String canonicalKey;

    @TableField("content")
    private String content;

    @TableField("content_hash")
    private String contentHash;

    @TableField("confidence")
    private BigDecimal confidence;

    @TableField("visibility")
    private String visibility;

    @TableField("retention_type")
    private String retentionType;

    @TableField("status")
    private String status;

    @TableField("source_conversation_id")
    private String sourceConversationId;

    @TableField("source_message_sequence")
    private Long sourceMessageSequence;

    @TableField("evidence_text")
    private String evidenceText;

    @TableField("version")
    private Long version;

    @TableField("expires_at")
    private LocalDateTime expiresAt;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
