package com.xjjk.agent.knowledge.service;

import com.xjjk.agent.identity.domain.AgentIdentity;
import com.xjjk.agent.knowledge.domain.KnowledgeRetrievalResult;

import java.util.List;

/** 知识检索防腐层，调用方只能传问题和可选知识库范围，身份来自认证上下文。 */
@FunctionalInterface
public interface KnowledgeQueryGateway {
    KnowledgeRetrievalResult retrieve(
            String question,
            List<Long> knowledgeBaseIds,
            AgentIdentity identity,
            String requestId);
}
