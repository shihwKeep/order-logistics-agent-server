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

        // 必须先从 MySQL 取得可信游标，再访问 Redis；不能直接使用前端参数拼缓存 Key。
        ChatHistoryCursor cursor = cursorLoader.loadForRequest(turn);
        Optional<ChatHistorySnapshot> cached = cache.get(cursor);

        if (cached.isPresent()) {
            // 命中后省去历史消息元信息筛选和正文回表，但前面的归属校验仍然保留。
            return cached.get();
        }

        // 缓存关闭、未命中、超时或坏值都统一走这里回源，保证 Redis 故障不阻断聊天。
        Timer.Sample sample = metrics.startTimer();
        ChatHistorySnapshot snapshot;
        try {
            snapshot = historyLoader.load(cursor);
        } finally {
            metrics.recordDatabaseLoadDuration(sample);
        }

        // Read-Through 补写是尽力而为；失败时仍返回本次数据库快照。
        cache.put(cursor, snapshot);
        return snapshot;
    }
}
