package com.xjjk.agent.tool;

/** 把工具产生的完整结构化结果发布到当前请求的输出通道。 */
@FunctionalInterface
public interface ToolOutputPublisher {
    void publish(ToolUiResult result);
}
