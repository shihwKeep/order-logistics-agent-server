package com.xjjk.agent.memory.index;

import com.xjjk.agent.memory.domain.MemoryOutboxOperation;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeMemoryIndexGatewayTest {

    @Test
    void signsAndSendsAnIndexCommand() {
        UserMemoryKnowledgeClient client = mock(UserMemoryKnowledgeClient.class);
        MemoryKnowledgeRequestSigner signer = mock(MemoryKnowledgeRequestSigner.class);
        when(signer.sign(anyString(), anyLong(), anyLong(), anyString()))
                .thenReturn(new MemoryKnowledgeRequestSigner.SignedHeaders(
                        123L, "nonce", "signature"));
        when(client.index(anyLong(), anyLong(), anyLong(), anyString(), anyString(), any()))
                .thenReturn(new UserMemoryKnowledgeClient.ServiceResponse<>(
                        "SUCCESS", "success",
                        new UserMemoryKnowledgeClient.IndexData("event-1", "APPLIED")));
        KnowledgeMemoryIndexGateway gateway =
                new KnowledgeMemoryIndexGateway(client, signer);

        gateway.apply(new MemoryIndexCommand(
                7L, 9L, "event-1", MemoryOutboxOperation.UPSERT, 3L, "memory-1", 2L,
                "AUTO_EXTRACT", "PROFILE", "primary_language", "主要使用 Java",
                0.91D, Instant.parse("2027-09-12T01:02:03Z")));

        ArgumentCaptor<UserMemoryKnowledgeClient.IndexRequest> body =
                ArgumentCaptor.forClass(UserMemoryKnowledgeClient.IndexRequest.class);
        verify(client).index(eq(7L), eq(9L), eq(123L), eq("nonce"),
                eq("signature"), body.capture());
        assertThat(body.getValue().eventId()).isEqualTo("event-1");
        assertThat(body.getValue().content()).isEqualTo("主要使用 Java");
        verify(signer).sign(eq(MemoryKnowledgeRequestSigner.INDEX_PATH),
                eq(7L), eq(9L), eq(body.getValue().payloadDigest()));
    }

    @Test
    void rejectsAnInvalidOrMismatchedResponseSoOutboxCanRetry() {
        UserMemoryKnowledgeClient client = mock(UserMemoryKnowledgeClient.class);
        MemoryKnowledgeRequestSigner signer = mock(MemoryKnowledgeRequestSigner.class);
        when(signer.sign(anyString(), anyLong(), anyLong(), anyString()))
                .thenReturn(new MemoryKnowledgeRequestSigner.SignedHeaders(
                        123L, "nonce", "signature"));
        when(client.index(anyLong(), anyLong(), anyLong(), anyString(), anyString(), any()))
                .thenReturn(new UserMemoryKnowledgeClient.ServiceResponse<>(
                        "SUCCESS", "success",
                        new UserMemoryKnowledgeClient.IndexData("other-event", "APPLIED")));
        KnowledgeMemoryIndexGateway gateway =
                new KnowledgeMemoryIndexGateway(client, signer);

        assertThatThrownBy(() -> gateway.apply(MemoryIndexCommand.clearGeneration(
                7L, 9L, "event-1", 3L)))
                .isInstanceOf(MemoryIndexUnavailableException.class);
    }
}
