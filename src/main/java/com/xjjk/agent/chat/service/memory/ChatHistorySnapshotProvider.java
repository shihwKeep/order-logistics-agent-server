package com.xjjk.agent.chat.service.memory;

import com.xjjk.agent.chat.cache.ChatHistorySnapshotCache;
import com.xjjk.agent.chat.domain.ChatTurnContext;
import com.xjjk.agent.chat.domain.memory.ChatHistoryCursor;
import com.xjjk.agent.chat.domain.memory.ChatHistorySnapshot;
import com.xjjk.agent.chat.observation.ChatHistoryCacheMetrics;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;

/**
 * 会话历史快照统一提供器。
 *
 * 先通过 MySQL 验证请求和稳定游标，再在事务外读取 Redis；
 * 缓存未命中时回源 MySQL，并尽力补写缓存。
 */
@Service
@RequiredArgsConstructor
public class ChatHistorySnapshotProvider {

    private final ChatHistoryCursorLoader cursorLoader;
    private final ChatHistorySnapshotCache cache;
    private final ChatHistoryLoader historyLoader;
    private final ChatHistoryCacheMetrics metrics;

    public ChatHistorySnapshot load(ChatTurnContext turn) {
        Objects.requireNonNull(turn, "本轮上下文不能为空");

        ChatHistoryCursor cursor = cursorLoader.loadForRequest(turn);
        Optional<ChatHistorySnapshot> cached = cache.get(cursor);

        if (cached.isPresent()) {
            return cached.get();
        }

        Timer.Sample sample = metrics.startTimer();
        ChatHistorySnapshot snapshot;
        try {
            snapshot = historyLoader.load(cursor);
        } finally {
            metrics.recordDatabaseLoadDuration(sample);
        }

        cache.put(cursor, snapshot);
        return snapshot;
    }
}
