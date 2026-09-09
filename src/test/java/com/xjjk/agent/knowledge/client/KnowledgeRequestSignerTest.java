package com.xjjk.agent.knowledge.client;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeRequestSignerTest {

    @Test
    void signsTheExactCanonicalContractUsedByKnowledgeService() {
        KnowledgeRequestSigner signer = new KnowledgeRequestSigner(
                "0123456789abcdef0123456789abcdef",
                Clock.fixed(Instant.ofEpochMilli(1_789_000_000_123L), ZoneOffset.UTC),
                () -> "nonce-001");

        KnowledgeRequestSigner.SignedHeaders signed = signer.sign(
                7L, 10567L, "  退款规则是什么  ", List.of(9L, 2L, 9L));

        assertThat(signed.timestamp()).isEqualTo(1_789_000_000_123L);
        assertThat(signed.nonce()).isEqualTo("nonce-001");
        assertThat(signed.signature())
                .isEqualTo("dacc4900933f760591c350f3410d6f5c29534868b9fb103cd418293dad23f4e5");
    }
}
