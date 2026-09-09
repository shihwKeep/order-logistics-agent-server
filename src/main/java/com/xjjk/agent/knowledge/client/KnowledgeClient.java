package com.xjjk.agent.knowledge.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.List;
import java.util.Set;

@FeignClient(
        name = "agent-knowledge",
        url = "${integration.knowledge.base-url:http://127.0.0.1:9}",
        configuration = KnowledgeFeignConfiguration.class)
public interface KnowledgeClient {

    @PostMapping("/api/v1/internal/knowledge/retrieve")
    ServiceResponse<RetrievalData> retrieve(
            @RequestHeader("X-Knowledge-Tenant-Id") long tenantId,
            @RequestHeader("X-Knowledge-User-Id") long userId,
            @RequestHeader("X-Knowledge-Timestamp") long timestamp,
            @RequestHeader("X-Knowledge-Nonce") String nonce,
            @RequestHeader("X-Knowledge-Signature") String signature,
            @RequestHeader("X-Request-Id") String requestId,
            @RequestBody RetrievalRequest request);

    record RetrievalRequest(String question, List<Long> knowledgeBaseIds) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ServiceResponse<T>(String code, String message, T data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RetrievalData(
            Boolean answerable,
            List<EvidenceData> evidences,
            String strategyVersion,
            String degradationMode,
            String resultCode) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EvidenceData(
            Long knowledgeBaseId,
            Long documentId,
            Long versionId,
            String chunkId,
            String documentTitle,
            String titlePath,
            String content,
            String locationJson,
            Double score,
            Set<String> sources) {}
}
