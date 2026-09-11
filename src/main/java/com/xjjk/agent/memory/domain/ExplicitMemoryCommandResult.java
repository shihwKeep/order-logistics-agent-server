package com.xjjk.agent.memory.domain;

/** 显式记忆命令对聊天执行器返回的确定性结果。 */
public record ExplicitMemoryCommandResult(
        boolean handled,
        boolean saved,
        String assistantText,
        String memoryId
) {

    public static ExplicitMemoryCommandResult notHandled() {
        return new ExplicitMemoryCommandResult(false, false, null, null);
    }
}
