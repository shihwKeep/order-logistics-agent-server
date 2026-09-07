package com.xjjk.agent.tool;

/** 单轮请求尝试调用的不同工具参数组合超过安全上限。 */
public class ToolCallLimitExceededException extends RuntimeException {

    public ToolCallLimitExceededException(int maxDistinctCalls) {
        super("单轮工具调用种类超过上限: " + maxDistinctCalls);
    }
}
