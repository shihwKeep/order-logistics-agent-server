package com.xjjk.agent.chat.persistence.projection;

import lombok.Getter;
import lombok.Setter;

/**
 * 长期摘要候选消息的轻量元信息。
 *
 * 第一阶段查询不读取正文，只用于验证轮次连续性、状态和读取预算。
 */
@Getter
@Setter
public class ChatSummaryMessageMetadata {

    /** 对外消息 ID。 */
    private String messageId;

    /** 同一问答轮次的请求 ID。 */
    private String requestId;

    /** 会话内消息序号。 */
    private Long messageSequence;

    /** USER 或 ASSISTANT。 */
    private String role;

    /** 消息处理状态。 */
    private String status;

    /** 数据库正文 UTF-8 字节数。 */
    private Long contentBytes;
}
