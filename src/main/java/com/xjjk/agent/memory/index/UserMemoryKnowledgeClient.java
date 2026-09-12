package com.xjjk.agent.memory.index;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.xjjk.agent.knowledge.client.KnowledgeFeignConfiguration;
import com.xjjk.agent.memory.service.MemoryHashing;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

@FeignClient(
        name = "agent-user-memory-knowledge",
        url = "${integration.knowledge.base-url:http://127.0.0.1:9}",
        configuration = KnowledgeFeignConfiguration.class)
public interface UserMemoryKnowledgeClient {

    @PostMapping(MemoryKnowledgeRequestSigner.INDEX_PATH)
    ServiceResponse<IndexData> index(
            @RequestHeader("X-Knowledge-Tenant-Id") long tenantId,
            @RequestHeader("X-Knowledge-User-Id") long userId,
            @RequestHeader("X-Knowledge-Timestamp") long timestamp,
            @RequestHeader("X-Knowledge-Nonce") String nonce,
            @RequestHeader("X-Knowledge-Signature") String signature,
            @RequestBody IndexRequest request);

    @PostMapping(MemoryKnowledgeRequestSigner.RETRIEVE_PATH)
    ServiceResponse<RetrieveData> retrieve(
            @RequestHeader("X-Knowledge-Tenant-Id") long tenantId,
            @RequestHeader("X-Knowledge-User-Id") long userId,
            @RequestHeader("X-Knowledge-Timestamp") long timestamp,
            @RequestHeader("X-Knowledge-Nonce") String nonce,
            @RequestHeader("X-Knowledge-Signature") String signature,
            @RequestBody RetrieveRequest request);

    record IndexRequest(
            String eventId,
            String operation,
            long memoryGeneration,
            String memoryId,
            long memoryVersion,
            String sourceType,
            String category,
            String canonicalKey,
            String content,
            double confidence,
            Instant expiresAt) {

        public String payloadDigest() {
            String canonical = String.join("\n",
                    value(eventId),
                    value(operation),
                    Long.toString(memoryGeneration),
                    value(memoryId),
                    Long.toString(memoryVersion),
                    value(sourceType),
                    value(category),
                    value(canonicalKey),
                    MemoryHashing.sha256(value(content)),
                    BigDecimal.valueOf(confidence).stripTrailingZeros().toPlainString(),
                    expiresAt == null ? "" : expiresAt.toString());
            return MemoryHashing.sha256(canonical);
        }
    }

    record RetrieveRequest(String query, long memoryGeneration) {
        public String payloadDigest() {
            return MemoryHashing.sha256(
                    memoryGeneration + "\n" + MemoryHashing.sha256(query.trim()));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ServiceResponse<T>(String code, String message, T data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record IndexData(String eventId, String resultCode) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RetrieveData(
            List<CandidateData> candidates,
            String strategyVersion,
            String degradationMode,
            String resultCode) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CandidateData(
            String memoryId,
            long memoryVersion,
            double score,
            int rank,
            Set<String> sources) {
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
