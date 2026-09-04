package com.xjjk.agent.chat.domain;

/**
 * 持久化消息的角色。
 * 当前阶段只保存用户消息和助手消息。
 */
public enum MessageRole {

    /** 用户提交的问题。 */
    USER,

    /** 助手生成的回答。 */
    ASSISTANT
}
