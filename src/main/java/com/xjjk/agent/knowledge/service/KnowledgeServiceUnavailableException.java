package com.xjjk.agent.knowledge.service;

/** Knowledge Service 网络、协议或响应边界异常的稳定业务语义。 */
public final class KnowledgeServiceUnavailableException extends RuntimeException {
    public KnowledgeServiceUnavailableException() {
        super("知识检索服务暂时不可用");
    }

    public KnowledgeServiceUnavailableException(Throwable cause) {
        super("知识检索服务暂时不可用", cause);
    }
}
