package com.xjjk.agent.chat.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Objects;
import java.util.regex.Pattern;

/** 使用独立 key 前缀保存复合查询 checkpoint，不与 SSE 回放共用 key。 */
@Component
public final class RedisCompositeQueryCheckpointStore
        implements CompositeQueryCheckpointStore {

    private static final Pattern SAFE_THREAD_ID = Pattern.compile("^[A-Za-z0-9-]{1,128}$");

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final CompositeQueryCheckpointProperties properties;

    public RedisCompositeQueryCheckpointStore(
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            CompositeQueryCheckpointProperties properties) {
        this.redis = Objects.requireNonNull(redis, "Redis 客户端不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "JSON 映射器不能为空");
        this.properties = Objects.requireNonNull(properties, "checkpoint 配置不能为空");
    }

    @Override
    public Optional<CompositeQueryCheckpoint> load(String threadId) {
        if (!properties.enabled()) {
            return Optional.empty();
        }
        String key = key(threadId);
        try {
            String json = redis.opsForValue().get(key);
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }
            CompositeQueryCheckpoint checkpoint = objectMapper.readValue(
                    json, CompositeQueryCheckpoint.class);
            return properties.graphVersion().equals(checkpoint.graphVersion())
                    ? Optional.of(checkpoint) : Optional.empty();
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            return Optional.empty();
        } catch (RuntimeException exception) {
            throw new CompositeQueryCheckpointUnavailableException(
                    "复合查询 checkpoint 读取失败", exception);
        }
    }

    @Override
    public void save(CompositeQueryCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint 不能为空");
        if (!properties.enabled()) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(checkpoint);
            redis.opsForValue().set(
                    key(checkpoint.threadId()), json, properties.ttl());
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalArgumentException("复合查询 checkpoint 序列化失败", exception);
        } catch (RuntimeException exception) {
            throw new CompositeQueryCheckpointUnavailableException(
                    "复合查询 checkpoint 保存失败", exception);
        }
    }

    @Override
    public void delete(String threadId) {
        if (!properties.enabled()) {
            return;
        }
        try {
            redis.delete(key(threadId));
        } catch (RuntimeException exception) {
            throw new CompositeQueryCheckpointUnavailableException(
                    "复合查询 checkpoint 删除失败", exception);
        }
    }

    private String key(String threadId) {
        if (threadId == null || !SAFE_THREAD_ID.matcher(threadId).matches()) {
            throw new IllegalArgumentException("复合查询 threadId 不合法");
        }
        return properties.keyPrefix() + ":{" + threadId + "}";
    }
}
