package com.xjjk.agent.chat.replay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xjjk.agent.chat.api.dto.ChatStreamPayloads;
import com.xjjk.agent.chat.config.ChatStreamProperties;
import com.xjjk.agent.identity.domain.AgentIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisChatReplayRepositoryTest {

    private static final AgentIdentity IDENTITY = new AgentIdentity(
            2L, "agent", "坐席", 3L, 1L);
    private static final String REQUEST_ID = "6f899318-0af5-4f2b-a593-84f6dac9dd1c";

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void readsOnlyEventsFollowingTheConfirmedSequence() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> streams = mock(StreamOperations.class);
        when(redis.opsForStream()).thenReturn(streams);
        MapRecord<String, Object, Object> record = StreamRecords
                .newRecord()
                .in("events")
                .ofMap(Map.<Object, Object>of(
                        "type", "delta",
                        "timestamp", "2026-09-14T08:00:01Z",
                        "payload", "{\"text\":\"后续\"}"))
                .withId(RecordId.of("8-0"));
        when(streams.read(any(StreamReadOptions.class), any(StreamOffset[].class)))
                .thenReturn(List.of(record));
        RedisChatReplayRepository repository = repository(redis);

        List<ChatReplayEvent> events = repository.readAfter(
                IDENTITY, REQUEST_ID, 7L, Duration.ofSeconds(5));

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.sequence()).isEqualTo(8L);
            assertThat(event.payload().get("text").asText()).isEqualTo("后续");
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void exposesTheCrossInstanceCancellationFlag() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hashes);
        when(hashes.get(any(), any())).thenReturn("true");
        RedisChatReplayRepository repository = repository(redis);

        assertThat(repository.cancellationRequested(IDENTITY, REQUEST_ID)).isTrue();
    }

    @Test
    void createsReplayMetadataIdempotently() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        doReturn(List.of("CREATED"))
                .when(redis)
                .execute(any(RedisScript.class), anyList(), any(Object[].class));
        RedisChatReplayRepository repository = repository(redis);

        ChatReplayCreateResult result = repository.create(metadata());

        assertThat(result).isEqualTo(ChatReplayCreateResult.CREATED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void readsAnOwnedStatusSnapshot() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hashes);
        when(hashes.entries(any())).thenReturn(Map.of(
                "tenantId", "1",
                "userId", "2",
                "conversationId", "conversation-1",
                "requestId", REQUEST_ID,
                "state", "DONE",
                "createdAt", "2026-09-14T08:00:00Z",
                "expiresAt", "2026-09-14T08:00:30Z",
                "lastSequence", "9",
                "terminalMessageId", "message-1"));
        RedisChatReplayRepository repository = repository(redis);

        ChatReplaySnapshot snapshot = repository.status(IDENTITY, REQUEST_ID).orElseThrow();

        assertThat(snapshot.state()).isEqualTo(ChatReplayState.DONE);
        assertThat(snapshot.lastSequence()).isEqualTo(9L);
        assertThat(snapshot.terminalMessageId()).isEqualTo("message-1");
    }

    @Test
    void recordsACrossInstanceCancellationRequest() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        doReturn(List.of("REQUESTED"))
                .when(redis)
                .execute(any(RedisScript.class), anyList(), any(Object[].class));
        RedisChatReplayRepository repository = repository(redis);

        assertThat(repository.requestCancel(IDENTITY, REQUEST_ID)).isTrue();
    }

    @Test
    void returnsTheSequenceAssignedByTheAtomicAppendScript() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        doReturn(List.of("OK", "7"))
                .when(redis)
                .execute(any(RedisScript.class), anyList(), any(Object[].class));
        RedisChatReplayRepository repository = repository(redis);

        ChatReplayEvent event = repository.append(
                IDENTITY,
                REQUEST_ID,
                "delta",
                new ChatStreamPayloads.Delta("回答"));

        assertThat(event.sequence()).isEqualTo(7L);
        assertThat(event.type()).isEqualTo("delta");
        assertThat(event.payload().get("text").asText()).isEqualTo("回答");
    }

    @Test
    void mapsRedisConnectionFailureToReplayUnavailable() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("offline"));
        RedisChatReplayRepository repository = repository(redis);

        assertThatThrownBy(() -> repository.append(
                IDENTITY,
                REQUEST_ID,
                "heartbeat",
                new ChatStreamPayloads.Heartbeat()))
                .isInstanceOf(ChatReplayUnavailableException.class)
                .hasMessageContaining("Redis");
    }

    private RedisChatReplayRepository repository(StringRedisTemplate redis) {
        return new RedisChatReplayRepository(
                redis,
                new ObjectMapper(),
                new ChatStreamProperties(
                        java.time.Duration.ofSeconds(30),
                        java.time.Duration.ofSeconds(10)));
    }

    private ChatReplayMetadata metadata() {
        Instant createdAt = Instant.parse("2026-09-14T08:00:00Z");
        return new ChatReplayMetadata(
                1L,
                2L,
                3L,
                "conversation-1",
                REQUEST_ID,
                createdAt,
                createdAt.plusSeconds(30));
    }
}
