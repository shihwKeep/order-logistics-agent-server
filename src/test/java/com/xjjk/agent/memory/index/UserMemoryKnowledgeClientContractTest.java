package com.xjjk.agent.memory.index;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class UserMemoryKnowledgeClientContractTest {

    @Test
    void indexPayloadDigestMatchesKnowledgeServiceContract() {
        UserMemoryKnowledgeClient.IndexRequest request =
                new UserMemoryKnowledgeClient.IndexRequest(
                        "event-1", "UPSERT", 3L, "memory-9", 2L,
                        "USER_EXPLICIT", "PREFERENCE", "preferred_name",
                        "用户希望被称为老师", 0.95D,
                        Instant.parse("2027-09-12T01:02:03Z"));

        assertThat(request.payloadDigest())
                .isEqualTo("87387c67c85ff3252dd2474eef6b2a691fb292ffb0bccf47cc2b911649d214c3");
    }

    @Test
    void retrievalPayloadDigestTrimsQueryAndResponseContainsSignalsOnly() {
        UserMemoryKnowledgeClient.RetrieveRequest request =
                new UserMemoryKnowledgeClient.RetrieveRequest("  我主要用什么语言？  ", 3L);
        UserMemoryKnowledgeClient.RetrieveData data =
                new UserMemoryKnowledgeClient.RetrieveData(
                        List.of(new UserMemoryKnowledgeClient.CandidateData(
                                "memory-9", 2L, 0.91D, 1, Set.of("VECTOR", "KEYWORD"))),
                        "memory-rrf-v1", "NONE", "OK");

        assertThat(request.payloadDigest())
                .isEqualTo("01f41bca69d4dd9d745f19c9efeb0c7270fe0f1cb9de006e517e4702871e175f");
        assertThat(data.candidates().getFirst()).extracting(
                        UserMemoryKnowledgeClient.CandidateData::memoryId,
                        UserMemoryKnowledgeClient.CandidateData::memoryVersion,
                        UserMemoryKnowledgeClient.CandidateData::score)
                .containsExactly("memory-9", 2L, 0.91D);
        assertThat(UserMemoryKnowledgeClient.CandidateData.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("content");
    }
}
