package com.xjjk.agent.chat.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 使用 Redis 保存复合查询最新 checkpoint 的基础设施实现。
 *
 * <p>checkpoint 与 SSE 回放解决不同问题：前者恢复后端 LangGraph4j 节点进度，后者补发
 * 已生成的前端事件，因此使用独立 key 前缀和独立 TTL，不能互相复用。</p>
 *
 * <pre>
 * key = keyPrefix + ":{" + threadId + "}"
 * threadId = 本轮 requestId
 * </pre>
 */
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

    /**
     * 读取指定 threadId 的最新 checkpoint。
     *
     * <p>不存在、空值、损坏 JSON 或图版本不匹配都表示没有可用恢复点；Redis 连接等
     * 基础设施异常则抛出专用异常，调用方不能把它误判为普通 MISS。</p>
     */
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
            // 图结构升级后旧 checkpoint 可能指向已经不存在或语义改变的节点，
            // 因此只有 graphVersion 完全一致时才允许恢复。
            return properties.graphVersion().equals(checkpoint.graphVersion())
                    ? Optional.of(checkpoint) : Optional.empty();
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            return Optional.empty();
        } catch (RuntimeException exception) {
            throw new CompositeQueryCheckpointUnavailableException(
                    "复合查询 checkpoint 读取失败", exception);
        }
    }

    /**
     * 将 checkpoint 序列化为 JSON，并使用配置中的 TTL 覆盖当前 threadId 的最新状态。
     * 每轮只保留一份最新状态，避免节点推进过程中产生无界历史记录。
     */
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

    /** 显式删除指定工作流状态；配置关闭时所有 Store 操作都保持无副作用。 */
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

    /**
     * 构造 Redis Key，并限制 threadId 只能包含公开安全字符。
     * 花括号保留同一 requestId 的 Redis Cluster hash tag 语义。
     */
    private String key(String threadId) {
        if (threadId == null || !SAFE_THREAD_ID.matcher(threadId).matches()) {
            throw new IllegalArgumentException("复合查询 threadId 不合法");
        }
        return properties.keyPrefix() + ":{" + threadId + "}";
    }
}
