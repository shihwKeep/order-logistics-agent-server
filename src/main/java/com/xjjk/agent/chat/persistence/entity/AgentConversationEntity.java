package com.xjjk.agent.chat.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Agent 会话实体，对应 agent_conversation 表。
 *
 * 每个会话归属于一个租户下的一个用户。
 * 查询和更新时必须校验会话归属，不能仅凭 conversationId 操作。
 */
@Getter
@Setter
@TableName("agent_conversation")
public class AgentConversationEntity {

    /** 数据库自增主键，不作为前端使用的会话标识。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 对外会话 ID，由后端生成 UUID。 */
    @TableField("conversation_id")
    private String conversationId;

    /** 所属租户 ID，由认证信息中的 companyId 映射得到。 */
    @TableField("tenant_id")
    private Long tenantId;

    /** 所属用户 ID，来自后端校验后的认证信息。 */
    @TableField("user_id")
    private Long userId;

    /**
     * 创建会话时的组织 ID 快照，来自认证身份。
     * 历史记录未知时为空，不作为当前组织权限的直接判定依据。
     */
    @TableField("org_id")
    private Long orgId;

    /** 会话标题，创建时默认使用“新对话”。 */
    @TableField("title")
    private String title;

    /** 已分配的最后一个消息序号；初始为 0，不是 SSE 事件序号。 */
    @TableField("last_message_sequence")
    private Long lastMessageSequence;

    /** 当前占用会话的请求 ID；为空表示没有记录占用请求。 */
    @TableField("active_request_id")
    private String activeRequestId;

    /** 当前请求占用的到期时间，用于后续处理超时占用，按 UTC 写入。 */
    @TableField("active_until")
    private LocalDateTime activeUntil;

    /** 创建时间，由应用按 UTC 写入。 */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /** 最后更新时间，由应用按 UTC 写入。 */
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}