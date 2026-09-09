package com.xjjk.agent.knowledge.domain;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

/** Knowledge Service 返回并通过 SSE 原样引用的可信来源快照。 */
public record KnowledgeRetrievalResult(
        boolean answerable,
        List<Evidence> evidences,
        String strategyVersion,
        String degradationMode,
        String resultCode,
        OffsetDateTime queriedAt) {

    public KnowledgeRetrievalResult {
        evidences = List.copyOf(evidences);
    }

    /** 单条引用的身份、可定位元数据和证据正文。 */
    public record Evidence(
            long knowledgeBaseId,
            long documentId,
            long versionId,
            String chunkId,
            String documentTitle,
            String titlePath,
            String content,
            String locationJson,
            double score,
            Set<String> sources) {
        public Evidence {
            sources = Set.copyOf(sources);
        }
    }
}
