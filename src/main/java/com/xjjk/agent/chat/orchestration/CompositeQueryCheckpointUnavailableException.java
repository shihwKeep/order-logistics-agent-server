package com.xjjk.agent.chat.orchestration;

/** checkpoint 存储不可用时的安全边界异常。 */
public class CompositeQueryCheckpointUnavailableException extends RuntimeException {
    public CompositeQueryCheckpointUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
