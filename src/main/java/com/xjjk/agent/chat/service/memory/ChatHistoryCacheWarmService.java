package com.xjjk.agent.chat.service.memory;

import com.xjjk.agent.chat.cache.ChatHistorySnapshotCache;
import com.xjjk.agent.chat.domain.memory.ChatHistoryCursor;
import com.xjjk.agent.chat.domain.memory.ChatHistorySnapshot;
import com.xjjk.agent.chat.observation.ChatHistoryCacheMetrics;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;

/**
 * 提交后的稳定历史缓存预热服务。
 *
 * 执行前重新读取 MySQL 游标，过时事件直接跳过。
 * 所有异常在后台隔离，不影响已经完成的聊天请求。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatHistoryCacheWarmService {

    private final ChatHistoryCursorLoader cursorLoader;
    private final ChatHistoryLoader historyLoader;
    private final ChatHistorySnapshotCache cache;
    private final ChatHistoryCacheMetrics metrics;

    public void warm(ChatHistoryChangedEvent event) {
        Objects.requireNonNull(event, "历史变更事件不能为空");

        Timer.Sample sample = metrics.startTimer();
        try {
            Optional<ChatHistoryCursor> cursor =
                    cursorLoader.loadForWarm(event);

            if (cursor.isEmpty()) {
                metrics.warmSkipped();
                log.debug(
                        "chat_history_cache_warm_skipped "
                                + "conversationId={}, memoryVersion={}",
                        event.conversationId(),
                        event.memoryVersion()
                );
                return;
            }

            ChatHistorySnapshot snapshot = historyLoader.load(
                    cursor.get()
            );
            cache.put(cursor.get(), snapshot);
            metrics.warmSuccess();
            log.debug(
                    "chat_history_cache_warm_completed "
                            + "conversationId={}, memoryVersion={}",
                    event.conversationId(),
                    event.memoryVersion()
            );
        } catch (RuntimeException exception) {
            metrics.warmError();
            log.warn(
                    "chat_history_cache_warm_failed "
                            + "conversationId={}, memoryVersion={}, "
                            + "exceptionType={}",
                    event.conversationId(),
                    event.memoryVersion(),
                    exception.getClass().getSimpleName()
            );
        } finally {
            metrics.recordWarmDuration(sample);
        }
    }
}
