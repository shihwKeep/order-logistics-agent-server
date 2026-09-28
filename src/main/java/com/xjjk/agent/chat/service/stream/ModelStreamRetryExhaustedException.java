package com.xjjk.agent.chat.service.stream;

/** 上游模型流的瞬时错误在有限重试后仍未恢复。 */
final class ModelStreamRetryExhaustedException extends RuntimeException {

    private final int attempts;

    ModelStreamRetryExhaustedException(int attempts, Throwable cause) {
        super("模型流重试次数已耗尽", cause);
        this.attempts = attempts;
    }

    int attempts() {
        return attempts;
    }
}
