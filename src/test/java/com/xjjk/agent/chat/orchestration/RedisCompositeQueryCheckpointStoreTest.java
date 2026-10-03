package com.xjjk.agent.chat.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisCompositeQueryCheckpointStoreTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> values;

    private RedisCompositeQueryCheckpointStore store;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
        store = new RedisCompositeQueryCheckpointStore(
                redis,
                new ObjectMapper(),
                new CompositeQueryCheckpointProperties(
                        true, "agent:composite:checkpoint:v2", "v2",
                        Duration.ofMinutes(10)));
    }

    @Test
    void saveUsesDedicatedPrefixAndTtl() {
        store.save(checkpoint("req-1"));

        verify(values).set(
                eq("agent:composite:checkpoint:v2:{req-1}"),
                anyString(), eq(Duration.ofMinutes(10)));
    }

    @Test
    void loadRejectsVersionMismatch() throws Exception {
        CompositeQueryCheckpoint invalid = new CompositeQueryCheckpoint(
                "v1", "req-1", "request-1", "conversation-1", "hash",
                Map.of(), List.of(), List.of(), Map.of(), "result.validate",
                "RUNNING", "2026-10-03T19:10:00+08:00");
        when(values.get("agent:composite:checkpoint:v2:{req-1}"))
                .thenReturn(new ObjectMapper().writeValueAsString(invalid));

        assertThat(store.load("req-1")).isEmpty();
    }

    @Test
    void redisFailureIsReportedAndNeverPretendsRecovery() {
        when(redis.opsForValue()).thenThrow(new QueryTimeoutException("redis timeout"));

        assertThatThrownBy(() -> store.load("req-1"))
                .isInstanceOf(CompositeQueryCheckpointUnavailableException.class);
    }

    @Test
    void missingValueIsEmpty() {
        when(values.get("agent:composite:checkpoint:v2:{req-1}")).thenReturn(null);

        assertThat(store.load("req-1")).isEqualTo(Optional.empty());
    }

    private CompositeQueryCheckpoint checkpoint(String threadId) {
        return new CompositeQueryCheckpoint(
                "v2", threadId, "request-1", "conversation-1", "hash",
                Map.of("finalStatus", "RUNNING"),
                List.of("input.validate"), List.of("result.validate"),
                Map.of("order.query", 0), "result.validate", "RUNNING",
                "2026-10-03T19:10:00+08:00");
    }
}
