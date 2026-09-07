package com.xjjk.agent.chat.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xjjk.agent.chat.config.ChatHistoryCacheProperties;
import com.xjjk.agent.chat.config.ChatHistoryProperties;
import com.xjjk.agent.chat.domain.memory.ChatHistoryCursor;
import com.xjjk.agent.chat.domain.memory.ChatHistorySnapshot;
import com.xjjk.agent.chat.domain.memory.ChatHistoryTurn;
import com.xjjk.agent.chat.observation.ChatHistoryCacheMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisChatHistorySnapshotCacheTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> values;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SimpleMeterRegistry meterRegistry =
            new SimpleMeterRegistry();

    private ChatHistoryCursor cursor;
    private ChatHistorySnapshot snapshot;
    private ChatHistoryCacheKeyFactory keyFactory;
    private RedisChatHistorySnapshotCache cache;

    @BeforeEach
    void setUp() {
        ChatHistoryCacheProperties cacheProperties =
                new ChatHistoryCacheProperties(
                        true,
                        "agent:chat:history:v1",
                        Duration.ofMinutes(30),
                        Duration.ofMinutes(5),
                        1,
                        2,
                        100
                );
        ChatHistoryProperties historyProperties =
                new ChatHistoryProperties(200, 1_048_576);
        keyFactory = new ChatHistoryCacheKeyFactory(
                cacheProperties,
                historyProperties
        );
        cache = new RedisChatHistorySnapshotCache(
                redis,
                objectMapper,
                keyFactory,
                cacheProperties,
                historyProperties,
                new ChatHistoryCacheMetrics(meterRegistry)
        );
        cursor = new ChatHistoryCursor(
                1, 10567, "conversation-1", 8, 16, 17);
        snapshot = new ChatHistorySnapshot(
                1,
                10567,
                "conversation-1",
                8,
                16,
                17,
                List.of(new ChatHistoryTurn(
                        "request-1", 15, 16, "问题", "回答")),
                false,
                false
        );
    }

    @Test
    void validValueIsReturnedAsDomainSnapshot() throws Exception {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(keyFactory.create(cursor))).thenReturn(
                objectMapper.writeValueAsString(
                        CachedChatHistorySnapshot.fromDomain(snapshot)
                )
        );

        assertThat(cache.get(cursor)).contains(snapshot);
        assertThat(counter("chat.history.cache.hit")).isEqualTo(1.0);
    }

    @Test
    void missingValueIsCacheMiss() {
        when(redis.opsForValue()).thenReturn(values);

        assertThat(cache.get(cursor)).isEmpty();
        assertThat(counter("chat.history.cache.miss")).isEqualTo(1.0);
    }

    @Test
    void redisReadFailureBecomesCacheMiss() {
        when(redis.opsForValue()).thenThrow(
                new QueryTimeoutException("redis timeout"));

        assertThat(cache.get(cursor)).isEmpty();
        assertThat(counter("chat.history.cache.error")).isEqualTo(1.0);
    }

    @Test
    void malformedJsonIsRejected() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(keyFactory.create(cursor))).thenReturn("{bad-json");

        assertThat(cache.get(cursor)).isEmpty();
        assertThat(counter("chat.history.cache.invalid")).isEqualTo(1.0);
    }

    @Test
    void mismatchedVersionIsRejected() throws Exception {
        CachedChatHistorySnapshot valid =
                CachedChatHistorySnapshot.fromDomain(snapshot);
        CachedChatHistorySnapshot invalid = new CachedChatHistorySnapshot(
                valid.schemaVersion(),
                valid.tenantId(),
                valid.userId(),
                valid.conversationId(),
                7,
                valid.memoryUntilSequence(),
                valid.beforeSequence(),
                valid.turns(),
                valid.hasEarlierMessages(),
                valid.readBudgetTruncated()
        );
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(keyFactory.create(cursor))).thenReturn(
                objectMapper.writeValueAsString(invalid)
        );

        assertThat(cache.get(cursor)).isEmpty();
        assertThat(counter("chat.history.cache.invalid")).isEqualTo(1.0);
    }

    @Test
    void writeFailureDoesNotEscape() {
        when(redis.opsForValue()).thenReturn(values);
        doThrow(new QueryTimeoutException("redis timeout"))
                .when(values)
                .set(anyString(), anyString(), any(Duration.class));

        assertThatCode(() -> cache.put(cursor, snapshot))
                .doesNotThrowAnyException();
        assertThat(counter("chat.history.cache.write.error"))
                .isEqualTo(1.0);
    }

    @Test
    void writeUsesConfiguredTtlRange() {
        when(redis.opsForValue()).thenReturn(values);

        cache.put(cursor, snapshot);

        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(values).set(
                anyString(),
                anyString(),
                ttl.capture()
        );
        assertThat(ttl.getValue()).isBetween(
                Duration.ofMinutes(30),
                Duration.ofMinutes(35)
        );
        assertThat(counter("chat.history.cache.write.success"))
                .isEqualTo(1.0);
    }

    private double counter(String name) {
        return meterRegistry.get(name).counter().count();
    }
}
