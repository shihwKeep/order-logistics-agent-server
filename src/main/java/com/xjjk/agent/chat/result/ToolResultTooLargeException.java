package com.xjjk.agent.chat.result;

/** 结构化结果超过配置的持久化和 SSE 统一大小上限。 */
public class ToolResultTooLargeException extends RuntimeException {

    public ToolResultTooLargeException(int actualBytes, int maxBytes) {
        super("结构化工具结果超过大小上限: actualBytes="
                + actualBytes + ", maxBytes=" + maxBytes);
    }
}
