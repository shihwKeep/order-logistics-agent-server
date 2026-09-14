package com.xjjk.agent.chat.replay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xjjk.agent.chat.config.ChatStreamProperties;
import com.xjjk.agent.chat.api.dto.ChatStreamPayloads;
import com.xjjk.agent.chat.observation.ChatStreamReplayMetrics;
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
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Redis Streams 聊天事件仓储。
 *
 * <p>元数据、事件流和控制状态使用相同的 Redis Cluster Hash Tag，Lua 脚本因此可以在
 * 一个分片内原子校验身份、推进事件序号并更新任务状态。Redis 只承担短期断点回放，
 * 最终消息仍以 MySQL 持久化结果为准。</p>
 */
@Component
public class RedisChatReplayRepository implements ChatReplayRepository {

    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> APPEND_SCRIPT;
    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> CREATE_SCRIPT;
    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> CANCEL_SCRIPT;
    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> ACTIVATE_CONNECTION_SCRIPT;
    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> BIND_CONVERSATION_SCRIPT;

    static {
        APPEND_SCRIPT = script("redis/chat-stream-append.lua");
        CREATE_SCRIPT = script("redis/chat-stream-create.lua");
        CANCEL_SCRIPT = script("redis/chat-stream-cancel.lua");
        ACTIVATE_CONNECTION_SCRIPT = script("redis/chat-stream-activate-connection.lua");
        BIND_CONVERSATION_SCRIPT = script("redis/chat-stream-bind-conversation.lua");
    }

    @Override
    public ChatReplayCreateResult create(ChatReplayMetadata metadata) {
        Objects.requireNonNull(metadata, "聊天流补发元数据不能为空");
        // 创建脚本同时初始化任务元数据和取消标志。同一个 requestId 再次到达时只返回
        // EXISTING，不会重复创建生产任务；身份不一致时由脚本直接拒绝。
        List<?> result = execute(
                CREATE_SCRIPT,
                List.of(
                        keys.meta(metadata.tenantId(), metadata.userId(), metadata.requestId()),
                        keys.control(metadata.tenantId(), metadata.userId(), metadata.requestId())),
                Long.toString(metadata.tenantId()),
                Long.toString(metadata.userId()),
                Long.toString(metadata.orgId()),
                metadata.conversationId() == null ? "" : metadata.conversationId(),
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
        // 即使 Key 是由服务端工厂生成，也不能用 Key 命中替代租户和用户归属校验。
        if (!Long.toString(identity.tenantId()).equals(text(values, "tenantId"))
                || !Long.toString(identity.userId()).equals(text(values, "userId"))) {
            throw new ChatReplayUnavailableException("聊天任务不存在或不可访问");
        }
        try {
            return Optional.of(new ChatReplaySnapshot(
                    nullableText(values, "conversationId"),
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
    public boolean bindConversation(
            AgentIdentity identity,
            String requestId,
            String conversationId
    ) {
        Objects.requireNonNull(identity, "认证身份不能为空");
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("会话 ID 不能为空");
        }
        List<?> result = execute(
                BIND_CONVERSATION_SCRIPT,
                List.of(keys.meta(identity.tenantId(), identity.userId(), requestId)),
                Long.toString(identity.tenantId()),
                Long.toString(identity.userId()),
                conversationId,
                Long.toString(properties.ttl().toMillis()));
        return ChatReplayLifecycleResult.bound(result);
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
    public boolean activateConnection(
            AgentIdentity identity,
            String requestId,
            String connectionId
    ) {
        Objects.requireNonNull(identity, "认证身份不能为空");
        if (connectionId == null || connectionId.isBlank()) {
            throw new IllegalArgumentException("连接 ID 不能为空");
        }
        // 每次初始连接或恢复连接都写入新的 connectionId。中继读取期间会持续校验它，
        // 因此后建立的连接能够淘汰旧连接，避免两条 SSE 同时向前端发送同一批事件。
        List<?> result = execute(
                ACTIVATE_CONNECTION_SCRIPT,
                List.of(keys.meta(identity.tenantId(), identity.userId(), requestId)),
                Long.toString(identity.tenantId()),
                Long.toString(identity.userId()),
                connectionId,
                Long.toString(properties.ttl().toMillis()));
        return ChatReplayLifecycleResult.activated(result);
    }

    @Override
    public boolean isActiveConnection(
            AgentIdentity identity,
            String requestId,
            String connectionId
    ) {
        Objects.requireNonNull(identity, "认证身份不能为空");
        if (connectionId == null || connectionId.isBlank()) {
            return false;
        }
        try {
            Object activeConnectionId = redis.opsForHash().get(
                    keys.meta(identity.tenantId(), identity.userId(), requestId),
                    "activeConnectionId");
            return connectionId.equals(activeConnectionId);
        } catch (DataAccessException exception) {
            throw new ChatReplayUnavailableException("Redis 聊天活动连接读取失败", exception);
        }
    }

    @Override
    public boolean cancellationRequested(AgentIdentity identity, String requestId) {
        Objects.requireNonNull(identity, "认证身份不能为空");
        try {
            // 生产线程轮询分布式取消标志，网络断开本身不会写入该标志；只有用户主动
            // 点击停止才会请求取消后台任务。
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
            // Stream ID 使用“业务 sequence-0”。XREAD 从已确认序号之后阻塞读取，既能
            // 实时转发新事件，也能在重连时补发断开期间已写入 Redis 的事件。
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
            // 严格递增校验用于尽早暴露坏数据或协议错误，防止前端游标倒退后重复拼接。
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
    private final ChatStreamReplayMetrics metrics;

    public RedisChatReplayRepository(
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            ChatStreamProperties streamProperties
    ) {
        this(redis, objectMapper, streamProperties, null);
    }

    @Autowired
    public RedisChatReplayRepository(
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            ChatStreamProperties streamProperties,
            ChatStreamReplayMetrics metrics
    ) {
        this.redis = Objects.requireNonNull(redis, "Redis 客户端不能为空");
        this.codec = new ChatReplayEventCodec(objectMapper);
        this.properties = Objects.requireNonNull(
                streamProperties, "聊天流配置不能为空").replay();
        this.keys = new ChatReplayKeyFactory(properties);
        this.metrics = metrics;
    }

    @Override
    public boolean available() {
        if (!properties.enabled()) return false;
        try {
            // 在创建业务消息前探测 Redis；此时不可用可以安全降级为不可恢复的直连 SSE。
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
        // Lua 在一次原子操作中完成容量校验、sequence 递增、XADD、统计更新和终态迁移，
        // 避免多个异步事件写入时出现重复序号或 done/error 被后续事件越过。
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
        try {
            long sequence = ChatReplayScriptResult.appendSequence(result);
            if (metrics != null) metrics.replayEvent(encoded.type(), encoded.byteLength());
            return codec.decode(sequence, Map.of(
                    "type", encoded.type(),
                    "timestamp", encoded.timestamp(),
                    "payload", encoded.payloadJson()));
        } catch (ChatReplayLimitException exception) {
            if (metrics != null) metrics.failure("capacity");
            throw exception;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private List<?> execute(DefaultRedisScript<List> script, List<String> scriptKeys,
                            String... arguments) {
        try {
            return redis.execute(script, scriptKeys, (Object[]) arguments);
        } catch (DataAccessException exception) {
            // Redis 基础设施异常统一转换为领域异常，由上层决定初始请求降级还是恢复失败。
            if (metrics != null) metrics.failure("redis");
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
        // 终态与事件在同一个 append 脚本中提交，status 接口不会观察到“终态事件已写入，
        // 但任务仍显示 RUNNING”的中间状态。
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
