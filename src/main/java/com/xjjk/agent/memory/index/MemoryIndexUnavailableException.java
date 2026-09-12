package com.xjjk.agent.memory.index;

public class MemoryIndexUnavailableException extends RuntimeException {
    public MemoryIndexUnavailableException() {
        super("用户记忆索引服务暂时不可用");
    }

    public MemoryIndexUnavailableException(Throwable cause) {
        super("用户记忆索引服务暂时不可用", cause);
    }
}
