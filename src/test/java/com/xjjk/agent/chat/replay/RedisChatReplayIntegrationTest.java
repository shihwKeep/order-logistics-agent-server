package com.xjjk.agent.chat.replay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xjjk.agent.chat.api.dto.ChatStreamPayloads;
import com.xjjk.agent.chat.config.ChatStreamProperties;
import com.xjjk.agent.identity.domain.AgentIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class RedisChatReplayIntegrationTest {

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7.4.11-alpine"))
            .withExposedPorts(6379);

    private LettuceConnectionFactory connectionFactory;
    private RedisChatReplayRepository repository;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(
                REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        repository = new RedisChatReplayRepository(
                redis,
                new ObjectMapper(),
                new ChatStreamProperties(Duration.ofSeconds(30), Duration.ofSeconds(10)));
    }

    @AfterEach
    void tearDown() {
        connectionFactory.destroy();
    }

    @Test
    void createsAppendsReadsAndCancelsAgainstRealRedis() {
        AgentIdentity identity = new AgentIdentity(2L, "agent", "坐席", 3L, 1L);
        String requestId = UUID.randomUUID().toString();
        Instant createdAt = Instant.now();
        ChatReplayMetadata metadata = new ChatReplayMetadata(
                1L, 2L, 3L, "conversation-1", requestId,
                createdAt, createdAt.plusSeconds(30));

        assertThat(repository.available()).isTrue();
        assertThat(repository.create(metadata)).isEqualTo(ChatReplayCreateResult.CREATED);
        assertThat(repository.create(metadata)).isEqualTo(ChatReplayCreateResult.EXISTING);
        assertThat(repository.activateConnection(identity, requestId, "connection-1")).isTrue();
        assertThat(repository.isActiveConnection(identity, requestId, "connection-1")).isTrue();
        assertThat(repository.activateConnection(identity, requestId, "connection-2")).isTrue();
        assertThat(repository.isActiveConnection(identity, requestId, "connection-1")).isFalse();
        assertThat(repository.isActiveConnection(identity, requestId, "connection-2")).isTrue();
        assertThat(repository.append(identity, requestId, "delta",
                new ChatStreamPayloads.Delta("回答")).sequence()).isEqualTo(1L);
        assertThat(repository.readAfter(identity, requestId, 0L, Duration.ofMillis(100)))
                .singleElement()
                .satisfies(event -> assertThat(event.payload().get("text").asText())
                        .isEqualTo("回答"));
        assertThat(repository.requestCancel(identity, requestId)).isTrue();
        assertThat(repository.cancellationRequested(identity, requestId)).isTrue();
        assertThat(repository.status(identity, requestId).orElseThrow().state())
                .isEqualTo(ChatReplayState.RUNNING);
        repository.append(identity, requestId, "done",
                new ChatStreamPayloads.Done("message-1"));
        ChatReplaySnapshot terminal = repository.status(identity, requestId).orElseThrow();
        assertThat(terminal.state()).isEqualTo(ChatReplayState.DONE);
        assertThat(terminal.terminalMessageId()).isEqualTo("message-1");
    }
}
