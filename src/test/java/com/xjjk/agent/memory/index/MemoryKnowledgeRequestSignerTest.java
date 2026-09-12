package com.xjjk.agent.memory.index;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryKnowledgeRequestSignerTest {

    @Test
    void signsTheExactCanonicalContractUsedByKnowledgeService() {
        MemoryKnowledgeRequestSigner signer = new MemoryKnowledgeRequestSigner(
                "0123456789abcdef0123456789abcdef",
                Clock.fixed(Instant.ofEpochMilli(1_789_000_000_123L), ZoneOffset.UTC),
                () -> "nonce-001");

        MemoryKnowledgeRequestSigner.SignedHeaders signed = signer.sign(
                MemoryKnowledgeRequestSigner.INDEX_PATH,
                7L, 10567L, "payload-sha256");

        assertThat(MemoryKnowledgeRequestSigner.canonical(
                MemoryKnowledgeRequestSigner.INDEX_PATH,
                7L, 10567L, signed.timestamp(), signed.nonce(), "payload-sha256"))
                .isEqualTo("POST\n/api/v1/internal/user-memories/index-events\n7\n10567\n"
                        + "1789000000123\nnonce-001\npayload-sha256");
        assertThat(signed.signature())
                .isEqualTo("dc3799de156b8c801818d343098f4e7bac5a5b4f2d4b0441e543dc554b0e4658");
    }

    @Test
    void createsANewNonceForEveryRequestAndRejectsUnknownPaths() {
        java.util.concurrent.atomic.AtomicInteger sequence =
                new java.util.concurrent.atomic.AtomicInteger();
        MemoryKnowledgeRequestSigner signer = new MemoryKnowledgeRequestSigner(
                "0123456789abcdef0123456789abcdef", Clock.systemUTC(),
                () -> "nonce-" + sequence.incrementAndGet());

        assertThat(signer.sign(MemoryKnowledgeRequestSigner.RETRIEVE_PATH,
                1L, 2L, "digest").nonce()).isEqualTo("nonce-1");
        assertThat(signer.sign(MemoryKnowledgeRequestSigner.RETRIEVE_PATH,
                1L, 2L, "digest").nonce()).isEqualTo("nonce-2");
        assertThatThrownBy(() -> signer.sign("/unexpected", 1L, 2L, "digest"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
