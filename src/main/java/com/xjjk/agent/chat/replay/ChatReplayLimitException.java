package com.xjjk.agent.chat.replay;

/** 单轮补发事件数量或字节数超过受控容量。 */
public class ChatReplayLimitException extends RuntimeException {

    public ChatReplayLimitException() {
        super("聊天流补发容量已达到上限");
    }
}
