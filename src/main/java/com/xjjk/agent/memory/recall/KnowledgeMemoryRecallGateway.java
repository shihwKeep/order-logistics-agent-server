package com.xjjk.agent.memory.recall;

import com.xjjk.agent.memory.config.MemoryRetrievalProperties;
import com.xjjk.agent.memory.index.MemoryKnowledgeRequestSigner;
import com.xjjk.agent.memory.index.UserMemoryKnowledgeClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Knowledge Service 记忆召回接口的降级防腐层。 */
@Slf4j
@Component
public class KnowledgeMemoryRecallGateway implements MemoryRecallGateway {
    private static final Set<String> ALLOWED_SOURCES = Set.of("VECTOR", "KEYWORD");

    private final UserMemoryKnowledgeClient client;
    private final MemoryKnowledgeRequestSigner signer;
    private final MemoryRetrievalProperties properties;

    public KnowledgeMemoryRecallGateway(
            UserMemoryKnowledgeClient client,
            MemoryKnowledgeRequestSigner signer,
            MemoryRetrievalProperties properties) {
        this.client = client;
        this.signer = signer;
        this.properties = properties;
    }

    @Override
    public MemoryRecallGatewayResult retrieve(
            long tenantId, long userId, long generation, String query) {
        if (tenantId <= 0 || userId <= 0 || generation <= 0
                || query == null || query.isBlank() || query.length() > 2_000) {
            throw new IllegalArgumentException("用户记忆召回参数不合法");
        }
        UserMemoryKnowledgeClient.RetrieveRequest request =
                new UserMemoryKnowledgeClient.RetrieveRequest(query.strip(), generation);
        MemoryKnowledgeRequestSigner.SignedHeaders signed = signer.sign(
                MemoryKnowledgeRequestSigner.RETRIEVE_PATH,
                tenantId, userId, request.payloadDigest());
        try {
            return map(client.retrieve(
                    tenantId, userId, signed.timestamp(), signed.nonce(),
                    signed.signature(), request));
        } catch (RuntimeException exception) {
            log.warn("memory_recall_gateway_failed tenantId={}, userId={}, exceptionType={}",
                    tenantId, userId, exception.getClass().getSimpleName());
            return MemoryRecallGatewayResult.unavailable();
        }
    }

    private MemoryRecallGatewayResult map(
            UserMemoryKnowledgeClient.ServiceResponse<UserMemoryKnowledgeClient.RetrieveData>
                    response) {
        if (response == null || !"SUCCESS".equals(response.code()) || response.data() == null) {
            throw new IllegalStateException("记忆召回响应不完整");
        }
        UserMemoryKnowledgeClient.RetrieveData data = response.data();
        if (data.candidates() == null
                || data.candidates().size() > properties.maxCandidates()) {
            throw new IllegalStateException("记忆召回候选数量不合法");
        }
        HashSet<String> ids = new HashSet<>();
        List<MemoryRecallCandidateSignal> candidates = data.candidates().stream()
                .map(value -> mapCandidate(value, ids)).toList();
        return new MemoryRecallGatewayResult(
                true, candidates, bounded(data.strategyVersion(), 128),
                bounded(data.degradationMode(), 64), bounded(data.resultCode(), 64));
    }

    private MemoryRecallCandidateSignal mapCandidate(
            UserMemoryKnowledgeClient.CandidateData value, Set<String> ids) {
        if (value == null || value.memoryId() == null || value.memoryId().isBlank()
                || value.memoryId().length() > 64 || !ids.add(value.memoryId())
                || value.memoryVersion() <= 0 || !Double.isFinite(value.score())
                || value.rank() <= 0 || value.rank() > properties.maxCandidates()
                || value.sources() == null || value.sources().isEmpty()
                || !ALLOWED_SOURCES.containsAll(value.sources())) {
            throw new IllegalStateException("记忆召回候选协议不合法");
        }
        return new MemoryRecallCandidateSignal(
                value.memoryId(), value.memoryVersion(), value.score(),
                value.rank(), value.sources());
    }

    private String bounded(String value, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalStateException("记忆召回响应元数据不合法");
        }
        return value;
    }
}
