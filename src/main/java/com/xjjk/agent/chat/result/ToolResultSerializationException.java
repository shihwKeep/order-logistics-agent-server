package com.xjjk.agent.chat.result;

/** 结构化结果无法安全序列化时使用的无下游正文异常。 */
public class ToolResultSerializationException extends RuntimeException {

    public ToolResultSerializationException() {
        super("结构化工具结果序列化失败");
    }
}
