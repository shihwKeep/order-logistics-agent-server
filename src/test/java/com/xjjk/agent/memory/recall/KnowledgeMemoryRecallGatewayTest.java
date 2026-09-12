package com.xjjk.agent.memory.recall;

import com.xjjk.agent.memory.index.MemoryKnowledgeRequestSigner;
import com.xjjk.agent.memory.index.UserMemoryKnowledgeClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeMemoryRecallGatewayTest {

    @Test
    void returnsOnlyValidatedCandidateSignals() {
        UserMemoryKnowledgeClient client = mock(UserMemoryKnowledgeClient.class);
        MemoryKnowledgeRequestSigner signer = mock(MemoryKnowledgeRequestSigner.class);
        when(signer.sign(anyString(), eq(7L), eq(9L), anyString()))
                .thenReturn(new MemoryKnowledgeRequestSigner.SignedHeaders(
                        123L, "nonce", "signature"));
        when(client.retrieve(eq(7L), eq(9L), eq(123L), eq("nonce"), eq("signature"),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(new UserMemoryKnowledgeClient.ServiceResponse<>(
                        "SUCCESS", "success", new UserMemoryKnowledgeClient.RetrieveData(
                        List.of(new UserMemoryKnowledgeClient.CandidateData(
                                "memory-1", 4L, 0.92D, 1, Set.of("VECTOR", "KEYWORD"))),
                        "memory-rrf-v1", "NONE", "OK")));
        KnowledgeMemoryRecallGateway gateway =
                new KnowledgeMemoryRecallGateway(client, signer, properties());

        MemoryRecallGatewayResult result = gateway.retrieve(
                7L, 9L, 3L, "我主要使用什么编程语言？");

        assertThat(result.available()).isTrue();
        assertThat(result.candidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.memoryId()).isEqualTo("memory-1");
            assertThat(candidate.memoryVersion()).isEqualTo(4L);
            assertThat(candidate.sources()).containsExactlyInAnyOrder("VECTOR", "KEYWORD");
        });
        verify(signer).sign(eq(MemoryKnowledgeRequestSigner.RETRIEVE_PATH),
                eq(7L), eq(9L), anyString());
    }

    @Test
    void degradesToEmptyCandidatesForRemoteOrProtocolFailure() {
        UserMemoryKnowledgeClient client = mock(UserMemoryKnowledgeClient.class);
        MemoryKnowledgeRequestSigner signer = mock(MemoryKnowledgeRequestSigner.class);
        when(signer.sign(anyString(), eq(7L), eq(9L), anyString()))
                .thenReturn(new MemoryKnowledgeRequestSigner.SignedHeaders(
                        123L, "nonce", "signature"));
        when(client.retrieve(eq(7L), eq(9L), eq(123L), eq("nonce"), eq("signature"),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(new UserMemoryKnowledgeClient.ServiceResponse<>(
                        "SUCCESS", "success", new UserMemoryKnowledgeClient.RetrieveData(
                        List.of(new UserMemoryKnowledgeClient.CandidateData(
                                "memory-1", 4L, Double.NaN, 1, Set.of("VECTOR"))),
                        "memory-rrf-v1", "NONE", "OK")));

        MemoryRecallGatewayResult result = new KnowledgeMemoryRecallGateway(
                client, signer, properties()).retrieve(
                7L, 9L, 3L, "我主要使用什么编程语言？");

        assertThat(result.available()).isFalse();
        assertThat(result.candidates()).isEmpty();
        assertThat(result.resultCode()).isEqualTo("UNAVAILABLE");
    }

    private com.xjjk.agent.memory.config.MemoryRetrievalProperties properties() {
        return new com.xjjk.agent.memory.config.MemoryRetrievalProperties(20, 5, 3);
    }
}
