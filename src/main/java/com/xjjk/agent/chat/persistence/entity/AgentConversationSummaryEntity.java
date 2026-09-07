package com.xjjk.agent.chat.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 会话当前长期摘要实体，对应 agent_conversation_summary 表。
 *
 * 每个会话只保存一份当前摘要；更新时必须同时校验归属、摘要版本和连续覆盖边界。
 */
@Getter
@Setter
@TableName("agent_conversation_summary")
public class AgentConversationSummaryEntity {

    /** 数据库自增主键。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 所属租户 ID。 */
    @TableField("tenant_id")
    private Long tenantId;

    /** 所属坐席用户 ID。 */
    @TableField("user_id")
    private Long userId;

    /** 所属会话 ID。 */
    @TableField("conversation_id")
    private String conversationId;

    /** 当前摘要版本，从 1 开始且只增不减。 */
    @TableField("summary_version")
    private Long summaryVersion;

    /** 摘要已经连续处理到的消息序号，只能向前推进。 */
    @TableField("covered_until_sequence")
    private Long coveredUntilSequence;

    /** 生成当前摘要时捕获的稳定历史版本。 */
    @TableField("source_memory_version")
    private Long sourceMemoryVersion;

    /** 摘要 JSON 的结构版本。 */
    @TableField("schema_version")
    private Integer schemaVersion;

    /** 经过应用校验和脱敏的结构化摘要 JSON。 */
    @TableField("content_json")
    private String contentJson;

    /** 生成当前摘要使用的提示词版本。 */
    @TableField("prompt_version")
    private String promptVersion;

    /** 生成当前摘要使用的模型名称。 */
    @TableField("model_name")
    private String modelName;

    /** 模型输入 Token；供应商未返回时为空。 */
    @TableField("input_tokens")
    private Long inputTokens;

    /** 模型输出 Token；供应商未返回时为空。 */
    @TableField("output_tokens")
    private Long outputTokens;

    /** 创建时间，由应用按 UTC 写入。 */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /** 更新时间，由应用按 UTC 写入。 */
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
