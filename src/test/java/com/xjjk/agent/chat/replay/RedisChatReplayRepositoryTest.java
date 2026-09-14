package com.xjjk.agent.chat.replay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xjjk.agent.chat.api.dto.ChatStreamPayloads;
import com.xjjk.agent.chat.config.ChatStreamProperties;
import com.xjjk.agent.identity.domain.AgentIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

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
}
