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
            // 异步任务可能排队，执行时必须重新查询 MySQL，不能直接相信事件产生时的版本。
            Optional<ChatHistoryCursor> cursor =
                    cursorLoader.loadForWarm(event);

            if (cursor.isEmpty()) {
                // 会话已删除或数据库已推进到更高版本时，旧事件没有预热价值。
                metrics.warmSkipped();
                log.debug(
                        "chat_history_cache_warm_skipped "
                                + "conversationId={}, memoryVersion={}",
                        event.conversationId(),
                        event.memoryVersion()
                );
                return;
            }

            // 事务外重新生成新版本的稳定快照，使下一轮请求能够直接命中对应 Key。
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
            // 预热属于性能优化，失败只记录指标；已经提交的消息和会话状态不能被回滚。
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
