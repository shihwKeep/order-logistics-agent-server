package com.xjjk.agent.chat.service.stream;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class ModelStreamRetryPolicyTest {

    private final ModelStreamRetryPolicy policy = new ModelStreamRetryPolicy(
            3, Duration.ofMillis(500), Duration.ofSeconds(2), 0.0);

    @Test
    void retriesTransientNetworkFailuresUntilAttemptLimit() {
        assertThat(policy.shouldRetry(new IOException("connection reset"), 1, false))
                .isTrue();
        assertThat(policy.shouldRetry(new TimeoutException("read timeout"), 2, false))
                .isTrue();
        assertThat(policy.shouldRetry(new IOException("connection reset"), 3, false))
                .isFalse();
    }

    @Test
    void doesNotRetryNonTransientFailuresOrAfterPlainTextWasSent() {
        assertThat(policy.shouldRetry(new IllegalArgumentException("bad request"), 1, false))
                .isFalse();
        assertThat(policy.shouldRetry(new IOException("connection reset"), 1, true))
                .isFalse();
    }

    @Test
    void exponentialBackoffIsBounded() {
        assertThat(policy.backoff(1)).isEqualTo(Duration.ofMillis(500));
        assertThat(policy.backoff(2)).isEqualTo(Duration.ofSeconds(1));
        assertThat(policy.backoff(3)).isEqualTo(Duration.ofSeconds(2));
        assertThat(policy.backoff(4)).isEqualTo(Duration.ofSeconds(2));
    }
}
