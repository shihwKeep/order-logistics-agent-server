package com.xjjk.agent.chat.persistence.projection;

import lombok.Getter;
import lombok.Setter;

/**
 * 历史消息的轻量查询结果。
 *
 * 不是数据库实体，不用于插入或更新。
 * 不包含消息正文，用于筛选完整轮次及控制正文读取量。
 */
@Getter
@Setter
public class ChatHistoryMessageMetadata {

    /** 对外消息 ID，用于后续精确读取正文。 */
    private String messageId;

    /** 所属问答请求 ID，用于关联同一轮的用户和助手消息。 */
    private String requestId;

    /** 会话内消息序号。 */
    private Long messageSequence;

    /** 消息角色，当前为 USER 或 ASSISTANT。 */
    private String role;

    /** 消息处理状态。 */
    private String status;

    /** 数据库中正文的字节长度，不是字符数，也不是 Token 数。 */
    private Long contentBytes;
}
