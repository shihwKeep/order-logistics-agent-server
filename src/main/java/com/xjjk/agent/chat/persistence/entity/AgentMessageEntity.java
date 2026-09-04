package com.xjjk.agent.chat.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Agent 消息实体，对应 agent_message 表。
 *
 * 一轮问答分别保存用户消息和助手消息，
 * 两条消息通过相同的 requestId 关联。
 *
 * 访问消息时必须校验租户、用户和会话归属。
 */
@Getter
@Setter
@TableName("agent_message")
public class AgentMessageEntity {

    /** 数据库自增主键，不作为对外消息标识。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 对外消息 ID，由后端生成 UUID。 */
    @TableField("message_id")
    private String messageId;

    /** 所属会话 ID，对应会话表的 conversation_id。 */
    @TableField("conversation_id")
    private String conversationId;

    /** 所属租户 ID，由认证信息中的 companyId 映射得到。 */
    @TableField("tenant_id")
    private Long tenantId;

    /** 消息归属用户 ID；助手消息也归属于发起请求的用户。 */
    @TableField("user_id")
    private Long userId;

    /** 本轮请求 ID，同一轮用户消息和助手消息使用相同值。 */
    @TableField("request_id")
    private String requestId;

    /** 会话内的消息序号，从 1 开始递增，不是 SSE 事件序号。 */
    @TableField("message_sequence")
    private Long messageSequence;

    /** 消息角色：USER 表示用户，ASSISTANT 表示助手。 */
    @TableField("role")
    private String role;

    /** 消息正文；生成中的助手消息初始为空字符串，不能为 null。 */
    @TableField("content")
    private String content;

    /** 消息状态，例如 GENERATING、SUCCESS、FAILED、TIMEOUT。 */
    @TableField("status")
    private String status;

    /** 模型结束原因，例如 STOP、LENGTH；未取得时为空。 */
    @TableField("finish_reason")
    private String finishReason;

    /** 业务错误码；正常消息为空，不存储供应商原始异常。 */
    @TableField("error_code")
    private String errorCode;

    /** 本次生成使用的系统提示词版本；用户消息为空。 */
    @TableField("prompt_version")
    private String promptVersion;

    /** 创建时间，由应用按 UTC 写入。 */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /** 最后更新时间，由应用按 UTC 写入。 */
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
