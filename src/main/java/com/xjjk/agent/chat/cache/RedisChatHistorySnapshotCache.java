package com.xjjk.agent.chat.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xjjk.agent.chat.config.ChatHistoryCacheProperties;
import com.xjjk.agent.chat.config.ChatHistoryProperties;
import com.xjjk.agent.chat.domain.memory.ChatHistoryCursor;
import com.xjjk.agent.chat.domain.memory.ChatHistorySnapshot;
import com.xjjk.agent.chat.domain.memory.ChatHistoryTurn;
import com.xjjk.agent.chat.observation.ChatHistoryCacheMetrics;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Redis 会话历史快照适配器。
 *
 * Redis 仅作为性能缓存，任何读取或写入故障都不能中断聊天主流程。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisChatHistorySnapshotCache
        implements ChatHistorySnapshotCache {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final ChatHistoryCacheKeyFactory keyFactory;
    private final ChatHistoryCacheProperties cacheProperties;
    private final ChatHistoryProperties historyProperties;
    private final ChatHistoryCacheMetrics metrics;

    @Override
    public Optional<ChatHistorySnapshot> get(ChatHistoryCursor cursor) {
        Objects.requireNonNull(cursor, "稳定历史游标不能为空");

        if (!cacheProperties.enabled()) {
            // 关闭缓存时保持与“未命中”相同的调用语义，上层无需增加开关分支。
            return Optional.empty();
        }

        Timer.Sample sample = metrics.startTimer();
        try {
            String json;
            try {
                // Redis 读取放在数据库游标校验之后，缓存本身不承担鉴权和归属校验。
                json = redis.opsForValue().get(keyFactory.create(cursor));
            } catch (RuntimeException exception) {
                // Fail-Open：Redis 是加速层，连接超时等基础设施异常只触发 MySQL 回源。
                metrics.error();
                log.warn(
                        "chat_history_cache_read_failed "
                                + "conversationId={}, exceptionType={}",
                        cursor.conversationId(),
                        exception.getClass().getSimpleName()
                );
                return Optional.empty();
            }

            if (json == null) {
                metrics.miss();
                return Optional.empty();
            }

            try {
                // 即使 Key 正确也不直接信任缓存正文，反序列化后仍校验身份、版本和容量。
                ChatHistorySnapshot snapshot = objectMapper.readValue(
                        json,
                        CachedChatHistorySnapshot.class
                ).toDomain();
                validate(cursor, snapshot);
                metrics.hit();
                return Optional.of(snapshot);
            } catch (JsonProcessingException | RuntimeException exception) {
                metrics.invalid();
                log.warn(
                        "chat_history_cache_invalid "
                                + "conversationId={}, memoryVersion={}, "
                                + "exceptionType={}",
                        cursor.conversationId(),
                        cursor.memoryVersion(),
                        exception.getClass().getSimpleName()
                );
                return Optional.empty();
            }
        } finally {
            metrics.recordReadDuration(sample);
        }
    }

    @Override
    public void put(
            ChatHistoryCursor cursor,
            ChatHistorySnapshot snapshot
    ) {
        Objects.requireNonNull(cursor, "稳定历史游标不能为空");
        Objects.requireNonNull(snapshot, "历史快照不能为空");

        if (!cacheProperties.enabled()) {
            return;
        }

        try {
            // 先验证再序列化，避免把身份串线或超过读取预算的快照写进 Redis。
            validate(cursor, snapshot);
            String json = objectMapper.writeValueAsString(
                    CachedChatHistorySnapshot.fromDomain(snapshot)
            );
            redis.opsForValue().set(
                    keyFactory.create(cursor),
                    json,
                    nextTtl()
            );
            metrics.writeSuccess();
        } catch (JsonProcessingException | RuntimeException exception) {
            // 写缓存失败不会抛给调用方：当前请求仍可使用刚从 MySQL 取得的历史继续执行。
            metrics.writeError();
            log.warn(
                    "chat_history_cache_write_failed "
                            + "conversationId={}, memoryVersion={}, "
                            + "exceptionType={}",
                    cursor.conversationId(),
                    cursor.memoryVersion(),
                    exception.getClass().getSimpleName()
            );
        }
    }

    private void validate(
            ChatHistoryCursor cursor,
            ChatHistorySnapshot snapshot
    ) {
        // 缓存值必须与刚从 MySQL 取得的稳定游标完全一致，防止串租户、串用户或读到旧版本。
        if (snapshot.tenantId() != cursor.tenantId()
                || snapshot.userId() != cursor.userId()
                || !snapshot.conversationId().equals(
                cursor.conversationId())
                || snapshot.memoryVersion() != cursor.memoryVersion()
                || snapshot.memoryUntilSequence()
                != cursor.memoryUntilSequence()
                || snapshot.beforeSequence() != cursor.beforeSequence()) {
            throw new IllegalArgumentException("缓存历史身份或游标不匹配");
        }

        if (snapshot.turns().size() >
                historyProperties.maxScanMessages() / 2) {
            throw new IllegalArgumentException("缓存历史轮次数量超过限制");
        }

        long contentBytes = 0L;
        for (ChatHistoryTurn turn : snapshot.turns()) {
            // 使用 UTF-8 实际字节数复核容量，而不是只按 Java 字符数量判断。
            contentBytes = Math.addExact(
                    contentBytes,
                    turn.userContent()
                            .getBytes(StandardCharsets.UTF_8).length
            );
            contentBytes = Math.addExact(
                    contentBytes,
                    turn.assistantContent()
                            .getBytes(StandardCharsets.UTF_8).length
            );

            if (contentBytes > historyProperties.maxReadBytes()) {
                throw new IllegalArgumentException(
                        "缓存历史正文超过读取预算"
                );
            }
        }
    }

    private Duration nextTtl() {
        long jitterMillis = cacheProperties.ttlJitter().toMillis();
        // 在基础 TTL 上增加随机抖动，避免大量会话在同一时刻失效并集中回源 MySQL。
        long additionalMillis = jitterMillis == 0
                ? 0
                : ThreadLocalRandom.current().nextLong(jitterMillis + 1);
        return cacheProperties.ttl().plusMillis(additionalMillis);
    }
}
