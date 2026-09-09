package com.xjjk.agent.knowledge.client;

import com.xjjk.agent.identity.domain.AgentIdentity;
import com.xjjk.agent.knowledge.service.KnowledgeServiceUnavailableException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeServiceGatewayTest {

    private final KnowledgeClient client = mock(KnowledgeClient.class);
    private final KnowledgeRequestSigner signer = new KnowledgeRequestSigner(
            "0123456789abcdef0123456789abcdef",
            Clock.fixed(Instant.ofEpochMilli(1_789_000_000_123L), ZoneOffset.UTC),
            () -> "nonce-001");
    private final KnowledgeServiceGateway gateway = new KnowledgeServiceGateway(client, signer);
    private final AgentIdentity identity = new AgentIdentity(
            10567L, "74680", "石海文", 23L, 7L);

    @Test
    void injectsTrustedIdentityAndMapsBoundedEvidence() {
        KnowledgeClient.RetrievalRequest body = new KnowledgeClient.RetrievalRequest(
                "退款规则是什么", List.of());
        when(client.retrieve(
                7L, 10567L, 1_789_000_000_123L, "nonce-001",
                "29f03608370af0de41f5ba867108d1f5bda4bf625dd1c11f006982b467e73312",
                "request-1", body)).thenReturn(success(true, List.of(evidence())));

        var result = gateway.retrieve("退款规则是什么", List.of(), identity, "request-1");

        assertThat(result.answerable()).isTrue();
        assertThat(result.evidences()).singleElement().satisfies(value -> {
            assertThat(value.documentTitle()).isEqualTo("售后退款规则");
            assertThat(value.sources()).containsExactlyInAnyOrder("VECTOR", "KEYWORD");
        });
        verify(client).retrieve(
                7L, 10567L, 1_789_000_000_123L, "nonce-001",
                "29f03608370af0de41f5ba867108d1f5bda4bf625dd1c11f006982b467e73312",
                "request-1", body);
    }

    @Test
    void rejectsContradictoryOrUnboundedResponses() {
        when(client.retrieve(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any())).thenReturn(success(false, List.of(evidence())));

        assertThatThrownBy(() -> gateway.retrieve(
                "退款规则是什么", List.of(), identity, "request-1"))
                .isInstanceOf(KnowledgeServiceUnavailableException.class)
                .hasMessage("知识检索服务暂时不可用");
    }

    private KnowledgeClient.ServiceResponse<KnowledgeClient.RetrievalData> success(
            boolean answerable, List<KnowledgeClient.EvidenceData> evidences) {
        return new KnowledgeClient.ServiceResponse<>(
                "SUCCESS", "success",
                new KnowledgeClient.RetrievalData(
                        answerable, evidences, "hybrid-v1", "NONE", "OK"));
    }

    private KnowledgeClient.EvidenceData evidence() {
        return new KnowledgeClient.EvidenceData(
                1L, 2L, 3L, "2:3:0", "售后退款规则", "退款 / 时限",
                "签收后七日内符合条件可申请退款。", "{\"pageNumber\":3}",
                0.91D, Set.of("VECTOR", "KEYWORD"));
    }
}
