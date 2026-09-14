package com.xjjk.agent.chat.replay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xjjk.agent.chat.config.ChatStreamProperties;
import com.xjjk.agent.chat.api.dto.ChatStreamPayloads;
import com.xjjk.agent.identity.domain.AgentIdentity;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Redis Streams 聊天事件仓储。 */
@Component
public class RedisChatReplayRepository implements ChatReplayRepository {

    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> APPEND_SCRIPT;
    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> CREATE_SCRIPT;
    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> CANCEL_SCRIPT;

    static {
        APPEND_SCRIPT = script("redis/chat-stream-append.lua");
        CREATE_SCRIPT = script("redis/chat-stream-create.lua");
        CANCEL_SCRIPT = script("redis/chat-stream-cancel.lua");
    }

    @Override
    public ChatReplayCreateResult create(ChatReplayMetadata metadata) {
        Objects.requireNonNull(metadata, "聊天流补发元数据不能为空");
        List<?> result = execute(
                CREATE_SCRIPT,
                List.of(
                        keys.meta(metadata.tenantId(), metadata.userId(), metadata.requestId()),
                        keys.control(metadata.tenantId(), metadata.userId(), metadata.requestId())),
                Long.toString(metadata.tenantId()),
                Long.toString(metadata.userId()),
                Long.toString(metadata.orgId()),
                metadata.conversationId(),
                metadata.requestId(),
                metadata.createdAt().toString(),
                metadata.expiresAt().toString(),
                Long.toString(properties.ttl().toMillis()));
        return ChatReplayLifecycleResult.created(result);
    }

    @Override
    public Optional<ChatReplaySnapshot> status(AgentIdentity identity, String requestId) {
        Objects.requireNonNull(identity, "认证身份不能为空");
        Map<Object, Object> values;
        try {
            values = redis.opsForHash().entries(
                    keys.meta(identity.tenantId(), identity.userId(), requestId));
        } catch (DataAccessException exception) {
            throw new ChatReplayUnavailableException("Redis 聊天任务状态读取失败", exception);
        }
        if (values.isEmpty()) return Optional.empty();
        if (!Long.toString(identity.tenantId()).equals(text(values, "tenantId"))
                || !Long.toString(identity.userId()).equals(text(values, "userId"))) {
            throw new ChatReplayUnavailableException("聊天任务不存在或不可访问");
        }
        try {
            return Optional.of(new ChatReplaySnapshot(
                    text(values, "conversationId"),
                    text(values, "requestId"),
                    ChatReplayState.valueOf(text(values, "state")),
                    Instant.parse(text(values, "createdAt")),
                    Instant.parse(text(values, "expiresAt")),
                    Long.parseLong(text(values, "lastSequence")),
                    nullableText(values, "terminalCode"),
                    nullableText(values, "terminalMessageId")));
        } catch (RuntimeException exception) {
            if (exception instanceof ChatReplayUnavailableException unavailable) {
                throw unavailable;
            }
            throw new ChatReplayUnavailableException("Redis 聊天任务状态不合法", exception);
        }
    }

    @Override
    public boolean requestCancel(AgentIdentity identity, String requestId) {
        Objects.requireNonNull(identity, "认证身份不能为空");
        List<?> result = execute(
                CANCEL_SCRIPT,
                List.of(
                        keys.meta(identity.tenantId(), identity.userId(), requestId),
                        keys.control(identity.tenantId(), identity.userId(), requestId)),
                Long.toString(identity.tenantId()),
                Long.toString(identity.userId()),
                Long.toString(properties.ttl().toMillis()));
        return ChatReplayLifecycleResult.cancelled(result);
    }

    @Override
    public boolean cancellationRequested(AgentIdentity identity, String requestId) {
        Objects.requireNonNull(identity, "认证身份不能为空");
        try {
            Object value = redis.opsForHash().get(
                    keys.control(identity.tenantId(), identity.userId(), requestId),
                    "cancelRequested");
            return "true".equals(value);
        } catch (DataAccessException exception) {
            throw new ChatReplayUnavailableException("Redis 聊天取消状态读取失败", exception);
        }
    }

    @Override
    public List<ChatReplayEvent> readAfter(
            AgentIdentity identity,
            String requestId,
            long afterSequence,
            Duration blockTimeout
    ) {
        Objects.requireNonNull(identity, "认证身份不能为空");
        if (afterSequence < 0) {
            throw new IllegalArgumentException("已确认事件序号不能小于零");
        }
        if (blockTimeout == null || blockTimeout.isNegative() || blockTimeout.isZero()) {
            throw new IllegalArgumentException("阻塞读取时间必须大于零");
        }
        String eventKey = keys.events(identity.tenantId(), identity.userId(), requestId);
        List<MapRecord<String, Object, Object>> records;
        try {
            records = redis.opsForStream().read(
                    StreamReadOptions.empty()
                            .count(properties.maxEvents())
                            .block(blockTimeout),
                    StreamOffset.create(
                            eventKey,
                            ReadOffset.from(afterSequence + "-0")));
        } catch (DataAccessException exception) {
            throw new ChatReplayUnavailableException("Redis 聊天事件读取失败", exception);
        }
        if (records == null || records.isEmpty()) return List.of();

        List<ChatReplayEvent> events = new ArrayList<>(records.size());
        long previous = afterSequence;
        for (MapRecord<String, Object, Object> record : records) {
            long sequence = sequence(record.getId().getValue());
            if (sequence <= previous) {
                throw new ChatReplayUnavailableException("Redis 聊天事件序号未严格递增");
            }
            events.add(codec.decode(sequence, record.getValue()));
            previous = sequence;
        }
        return List.copyOf(events);
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

    @Override
    public boolean available() {
        if (!properties.enabled()) return false;
        try {
            String pong = redis.execute((RedisCallback<String>) connection -> connection.ping());
            return "PONG".equalsIgnoreCase(pong);
        } catch (DataAccessException exception) {
            return false;
        }
    }

    @Override
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
        TerminalMetadata terminal = terminalMetadata(type, payload);
        List<?> result = execute(
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
                terminal.state(),
                terminal.code(),
                terminal.messageId());
        long sequence = ChatReplayScriptResult.appendSequence(result);
        return codec.decode(sequence, Map.of(
                "type", encoded.type(),
                "timestamp", encoded.timestamp(),
                "payload", encoded.payloadJson()));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private List<?> execute(DefaultRedisScript<List> script, List<String> scriptKeys,
                            String... arguments) {
        try {
            return redis.execute(script, scriptKeys, (Object[]) arguments);
        } catch (DataAccessException exception) {
            throw new ChatReplayUnavailableException("Redis 聊天流操作失败", exception);
        }
    }

    @SuppressWarnings("rawtypes")
    private static DefaultRedisScript<List> script(String location) {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(location));
        script.setResultType(List.class);
        return script;
    }

    private static String text(Map<Object, Object> values, String field) {
        String value = nullableText(values, field);
        if (value == null || value.isEmpty()) {
            throw new ChatReplayUnavailableException("Redis 聊天任务缺少字段: " + field);
        }
        return value;
    }

    private static String nullableText(Map<Object, Object> values, String field) {
        Object value = values.get(field);
        return value instanceof String text && !text.isEmpty() ? text : null;
    }

    private static long sequence(String recordId) {
        int separator = recordId == null ? -1 : recordId.indexOf('-');
        if (separator <= 0) {
            throw new ChatReplayUnavailableException("Redis Stream 事件 ID 不合法");
        }
        try {
            long value = Long.parseLong(recordId.substring(0, separator));
            if (value <= 0) throw new NumberFormatException("非正序号");
            return value;
        } catch (NumberFormatException exception) {
            throw new ChatReplayUnavailableException("Redis Stream 事件序号不合法", exception);
        }
    }

    private TerminalMetadata terminalMetadata(String type, Object payload) {
        if ("done".equals(type)) {
            if (!(payload instanceof ChatStreamPayloads.Done done)) {
                throw new IllegalArgumentException("done 事件负载不合法");
            }
            return new TerminalMetadata("DONE", "", done.messageId());
        }
        if (!"error".equals(type)) return new TerminalMetadata("", "", "");
        if (!(payload instanceof ChatStreamPayloads.Error error)) {
            throw new IllegalArgumentException("error 事件负载不合法");
        }
        String state = switch (error.code()) {
            case "CHAT_TIMEOUT" -> "TIMEOUT";
            case "CHAT_CANCELLED" -> "CANCELLED";
            default -> "ERROR";
        };
        return new TerminalMetadata(state, error.code(), "");
    }

    private record TerminalMetadata(String state, String code, String messageId) {
    }
}
