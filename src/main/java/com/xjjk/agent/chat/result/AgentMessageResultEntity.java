package com.xjjk.agent.chat.result;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** Agent 助手消息的结构化业务结果快照，对应 agent_message_result 表。 */
@Getter
@Setter
@TableName("agent_message_result")
public class AgentMessageResultEntity {

    /** 数据库自增主键，不对外暴露。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 结果所属租户，由收尾事务中的可信会话上下文覆盖写入。 */
    @TableField("tenant_id")
    private Long tenantId;

    /** 结果所属坐席用户，由认证上下文写入。 */
    @TableField("user_id")
    private Long userId;

    /** 结果所属会话 UUID。 */
    @TableField("conversation_id")
    private String conversationId;

    /** 产生结果的本轮请求 UUID。 */
    @TableField("request_id")
    private String requestId;

    /** 关联的助手消息 UUID。 */
    @TableField("message_id")
    private String messageId;

    /** 同一助手回答内从 1 开始递增的结构化结果序号。 */
    @TableField("result_sequence")
    private Integer resultSequence;

    /** 产生结果的 Spring AI 工具名。 */
    @TableField("tool_name")
    private String toolName;

    /** 前端选择卡片或时间线组件使用的结果类型。 */
    @TableField("kind")
    private String kind;

    /** 前端结构化结果协议版本。 */
    @TableField("schema_version")
    private Integer schemaVersion;

    /** 已脱敏、裁剪并完成大小校验的 JSON 数据快照。 */
    @TableField("payload_json")
    private String payloadJson;

    /** 业务数据真实查询时间，应用统一按 UTC 入库。 */
    @TableField("queried_at")
    private LocalDateTime queriedAt;

    /** 快照创建时间，应用统一按 UTC 入库。 */
    @TableField("created_at")
    private LocalDateTime createdAt;
}
