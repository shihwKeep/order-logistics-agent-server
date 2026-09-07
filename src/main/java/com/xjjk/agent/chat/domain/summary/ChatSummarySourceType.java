package com.xjjk.agent.chat.domain.summary;

/**
 * 摘要条目的原始消息来源。
 *
 * 只允许引用真实落库的用户消息或成功助手消息，
 * 不接受模型自行声明“已验证”等更高信任级别。
 */
public enum ChatSummarySourceType {

    /** 用户在会话中提交的消息。 */
    USER_MESSAGE,

    /** 已正常完成并保存的助手消息。 */
    ASSISTANT_MESSAGE
}
