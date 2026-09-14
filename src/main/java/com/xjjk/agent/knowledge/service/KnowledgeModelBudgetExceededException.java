package com.xjjk.agent.knowledge.service;

public final class KnowledgeModelBudgetExceededException extends RuntimeException {
    public KnowledgeModelBudgetExceededException() {
        super("知识检索模型本月额度已用尽");
    }
}
