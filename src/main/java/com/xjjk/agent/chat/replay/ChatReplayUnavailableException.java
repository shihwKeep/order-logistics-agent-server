package com.xjjk.agent.chat.replay;

/** Redis 补发基础设施不可用或返回不可解释数据。 */
public class ChatReplayUnavailableException extends RuntimeException {

    public ChatReplayUnavailableException(String message) {
        super(message);
    }

    public ChatReplayUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
