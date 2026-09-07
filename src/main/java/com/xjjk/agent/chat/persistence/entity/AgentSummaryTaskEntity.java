package com.xjjk.agent.chat.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 会话长期摘要任务实体，对应 agent_summary_task 表。
 *
 * 一条会话只有一个任务状态行，用于合并请求、持久化重试和多实例租约竞争。
 */
@Getter
@Setter
@TableName("agent_summary_task")
public class AgentSummaryTaskEntity {

    /** 数据库自增主键。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 摘要任务 UUID。 */
    @TableField("task_id")
    private String taskId;

    /** 所属租户 ID。 */
    @TableField("tenant_id")
    private Long tenantId;

    /** 所属坐席用户 ID。 */
    @TableField("user_id")
    private Long userId;

    /** 所属会话 ID。 */
    @TableField("conversation_id")
    private String conversationId;

    /** 最新待检查的稳定历史版本，只能向前推进。 */
    @TableField("requested_memory_version")
    private Long requestedMemoryVersion;

    /** 最新待检查版本对应的稳定消息边界，只能向前推进。 */
    @TableField("requested_until_sequence")
    private Long requestedUntilSequence;

    /**
     * 最后完成有效“是否需要摘要”判断的稳定历史版本。
     * 该字段是版本水位，不是消息覆盖边界。
     */
    @TableField("last_evaluated_memory_version")
    private Long lastEvaluatedMemoryVersion;

    /** 是否因上下文空档要求绕过普通触发阈值。 */
    @TableField("force_generation")
    private Boolean forceGeneration;

    /** 强制生成的低基数原因。 */
    @TableField("force_reason")
    private String forceReason;

    /** 当前任务状态。 */
    @TableField("status")
    private String status;

    /** 当前连续失败次数。 */
    @TableField("retry_count")
    private Integer retryCount;

    /** 最早允许再次执行的 UTC 时间。 */
    @TableField("next_run_at")
    private LocalDateTime nextRunAt;

    /** 当前领取者生成的租约 UUID。 */
    @TableField("lease_token")
    private String leaseToken;

    /** 当前处理实例标识。 */
    @TableField("locked_by")
    private String lockedBy;

    /** 当前租约失效的 UTC 时间。 */
    @TableField("locked_until")
    private LocalDateTime lockedUntil;

    /** 最后一次安全错误码，不保存供应商异常正文。 */
    @TableField("last_error_code")
    private String lastErrorCode;

    /** 创建时间，由应用按 UTC 写入。 */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /** 更新时间，由应用按 UTC 写入。 */
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
