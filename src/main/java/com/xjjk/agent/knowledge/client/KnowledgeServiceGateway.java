package com.xjjk.agent.knowledge.client;

import com.xjjk.agent.identity.domain.AgentIdentity;
import com.xjjk.agent.knowledge.domain.KnowledgeRetrievalResult;
import com.xjjk.agent.knowledge.service.KnowledgeQueryGateway;
import com.xjjk.agent.knowledge.service.KnowledgeServiceUnavailableException;
import com.xjjk.agent.knowledge.service.KnowledgeModelBudgetExceededException;
import com.xjjk.agent.integration.observation.DownstreamCallMetrics;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Knowledge Service 防腐层：签入可信身份并严格验证跨服务响应。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeServiceGateway implements KnowledgeQueryGateway {
    private static final int MAX_EVIDENCES = 5;
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");

    private final KnowledgeClient client;
    private final KnowledgeRequestSigner signer;
    private DownstreamCallMetrics downstreamMetrics;

    @org.springframework.beans.factory.annotation.Autowired
    void setDownstreamMetrics(DownstreamCallMetrics downstreamMetrics) {
        this.downstreamMetrics = downstreamMetrics;
    }

    @Override
    public KnowledgeRetrievalResult retrieve(
            String question,
            List<Long> knowledgeBaseIds,
            AgentIdentity identity,
            String requestId) {
        validateRequest(question, knowledgeBaseIds, identity, requestId);
        String normalizedQuestion = question.strip();
        List<Long> ids = knowledgeBaseIds == null ? List.of() : List.copyOf(knowledgeBaseIds);
        KnowledgeRequestSigner.SignedHeaders signed = signer.sign(
                identity.tenantId(), identity.userId(), normalizedQuestion, ids);
        java.util.function.Supplier<KnowledgeRetrievalResult> invocation = () -> {
            try {
            KnowledgeClient.ServiceResponse<KnowledgeClient.RetrievalData> response = client.retrieve(
                    identity.tenantId(), identity.userId(), signed.timestamp(), signed.nonce(),
                    signed.signature(), requestId,
                    new KnowledgeClient.RetrievalRequest(normalizedQuestion, ids));
                if (downstreamMetrics != null) {
                    downstreamMetrics.attempt("knowledge", "retrieve", 1, null, false);
                }
                return map(response);
            } catch (KnowledgeModelBudgetExceededException exception) {
            log.warn("knowledge_gateway_budget_exhausted requestId={}", requestId);
            throw exception;
            } catch (FeignException | KnowledgeServiceUnavailableException exception) {
            if (downstreamMetrics != null) {
                downstreamMetrics.attempt("knowledge", "retrieve", 1, exception, false);
            }
            log.warn("knowledge_gateway_failed requestId={}, exceptionType={}",
                    requestId, exception.getClass().getSimpleName());
            throw new KnowledgeServiceUnavailableException();
            } catch (RuntimeException exception) {
            if (downstreamMetrics != null) {
                downstreamMetrics.attempt("knowledge", "retrieve", 1, exception, false);
            }
            log.warn("knowledge_gateway_invalid_response requestId={}, exceptionType={}",
                    requestId, exception.getClass().getSimpleName());
            throw new KnowledgeServiceUnavailableException();
            }
        };
        return downstreamMetrics == null
                ? invocation.get()
                : downstreamMetrics.observe("knowledge", "retrieve", invocation);
    }

    private KnowledgeRetrievalResult map(
            KnowledgeClient.ServiceResponse<KnowledgeClient.RetrievalData> response) {
        if (response == null || !"SUCCESS".equals(response.code()) || response.data() == null) {
            throw new KnowledgeServiceUnavailableException();
        }
        KnowledgeClient.RetrievalData data = response.data();
        List<KnowledgeClient.EvidenceData> source = data.evidences();
        if (data.answerable() == null || source == null || source.size() > MAX_EVIDENCES
                || (data.answerable() && source.isEmpty())
                || (!data.answerable() && !source.isEmpty())) {
            throw new KnowledgeServiceUnavailableException();
        }
        List<KnowledgeRetrievalResult.Evidence> evidences = source.stream()
                .map(this::mapEvidence).toList();
        return new KnowledgeRetrievalResult(
                data.answerable(), evidences,
                boundedRequired(data.strategyVersion(), 128),
                boundedRequired(data.degradationMode(), 64),
                boundedRequired(data.resultCode(), 64),
                OffsetDateTime.now(DEFAULT_ZONE));
    }

    private KnowledgeRetrievalResult.Evidence mapEvidence(KnowledgeClient.EvidenceData value) {
        if (value == null || value.knowledgeBaseId() == null || value.knowledgeBaseId() <= 0
                || value.documentId() == null || value.documentId() <= 0
                || value.versionId() == null || value.versionId() <= 0
                || value.score() == null || !Double.isFinite(value.score())
                || value.sources() == null || value.sources().isEmpty()
                || !Set.of("VECTOR", "KEYWORD").containsAll(value.sources())) {
            throw new KnowledgeServiceUnavailableException();
        }
        return new KnowledgeRetrievalResult.Evidence(
                value.knowledgeBaseId(), value.documentId(), value.versionId(),
                boundedRequired(value.chunkId(), 128),
                boundedRequired(value.documentTitle(), 256),
                boundedOptional(value.titlePath(), 512),
                boundedRequired(value.content(), 8_000),
                boundedOptional(value.locationJson(), 2_048),
                value.score(), value.sources());
    }

    private void validateRequest(
            String question, List<Long> ids, AgentIdentity identity, String requestId) {
        Objects.requireNonNull(identity, "知识检索身份不能为空");
        if (identity.tenantId() <= 0 || identity.userId() <= 0
                || question == null || question.isBlank() || question.length() > 2_000
                || requestId == null || requestId.isBlank() || requestId.length() > 64
                || (ids != null && (ids.size() > 50
                || ids.stream().anyMatch(id -> id == null || id <= 0)))) {
            throw new IllegalArgumentException("知识检索参数不合法");
        }
    }

    private String boundedRequired(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max
                || value.codePoints().anyMatch(this::unsafeControl)) {
            throw new KnowledgeServiceUnavailableException();
        }
        return value;
    }

    private String boundedOptional(String value, int max) {
        if (value == null) return null;
        if (value.length() > max || value.codePoints().anyMatch(this::unsafeControl)) {
            throw new KnowledgeServiceUnavailableException();
        }
        return value;
    }

    /** 正文允许换行和制表，其余控制字符拒绝跨越服务边界。 */
    private boolean unsafeControl(int codePoint) {
        return Character.isISOControl(codePoint)
                && codePoint != '\n' && codePoint != '\r' && codePoint != '\t';
    }
}
