package com.xjjk.agent.chat.replay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xjjk.agent.chat.config.ChatStreamProperties;
import com.xjjk.agent.identity.domain.AgentIdentity;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Redis Streams 聊天事件仓储。 */
@Component
public class RedisChatReplayRepository {

    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> APPEND_SCRIPT;

    static {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/chat-stream-append.lua"));
        script.setResultType(List.class);
        APPEND_SCRIPT = script;
    }

    private final StringRedisTemplate redis;
    private final ChatReplayEventCodec codec;
    private final ChatReplayKeyFactory keys;
    private final ChatStreamProperties.Replay properties;

    public RedisChatReplayRepository(
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            ChatStreamProperties streamProperties
    ) {
        this.redis = Objects.requireNonNull(redis, "Redis 客户端不能为空");
        this.codec = new ChatReplayEventCodec(objectMapper);
        this.properties = Objects.requireNonNull(
                streamProperties, "聊天流配置不能为空").replay();
        this.keys = new ChatReplayKeyFactory(properties);
    }

    @SuppressWarnings("unchecked")
    public ChatReplayEvent append(
            AgentIdentity identity,
            String requestId,
            String type,
            Object payload
    ) {
        Objects.requireNonNull(identity, "认证身份不能为空");
        Instant timestamp = Instant.now();
        ChatReplayEncodedEvent encoded = codec.encode(type, timestamp, payload);
        String terminalState = terminalState(type, payload);
        List<?> result;
        try {
            result = redis.execute(
                    APPEND_SCRIPT,
                    List.of(
                            keys.meta(identity.tenantId(), identity.userId(), requestId),
                            keys.events(identity.tenantId(), identity.userId(), requestId),
                            keys.control(identity.tenantId(), identity.userId(), requestId)),
                    Long.toString(identity.tenantId()),
                    Long.toString(identity.userId()),
                    encoded.type(),
                    encoded.timestamp(),
                    encoded.payloadJson(),
                    Integer.toString(encoded.byteLength()),
                    Long.toString(properties.ttl().toMillis()),
                    Integer.toString(properties.maxEvents()),
                    Integer.toString(properties.maxEventBytes()),
                    Integer.toString(properties.maxStreamBytes()),
                    terminalState);
        } catch (DataAccessException exception) {
            throw new ChatReplayUnavailableException("Redis 聊天事件追加失败", exception);
        }
        long sequence = ChatReplayScriptResult.appendSequence(result);
        return codec.decode(sequence, Map.of(
                "type", encoded.type(),
                "timestamp", encoded.timestamp(),
                "payload", encoded.payloadJson()));
    }

    private String terminalState(String type, Object payload) {
        if ("done".equals(type)) return "DONE";
        if (!"error".equals(type)) return "";
        try {
            String code = codec.encode(type, Instant.EPOCH, payload)
                    .payloadJson();
            if (code.contains("TIMEOUT")) return "TIMEOUT";
            if (code.contains("CANCEL")) return "CANCELLED";
            return "ERROR";
        } catch (RuntimeException exception) {
            return "ERROR";
        }
    }
}
