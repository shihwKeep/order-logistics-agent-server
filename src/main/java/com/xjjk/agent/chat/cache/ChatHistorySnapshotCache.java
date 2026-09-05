package com.xjjk.agent.chat.cache;

import com.xjjk.agent.chat.domain.memory.ChatHistoryCursor;
import com.xjjk.agent.chat.domain.memory.ChatHistorySnapshot;

import java.util.Optional;

/**
 * 稳定会话历史快照缓存端口。
 */
public interface ChatHistorySnapshotCache {

    Optional<ChatHistorySnapshot> get(ChatHistoryCursor cursor);

    void put(ChatHistoryCursor cursor, ChatHistorySnapshot snapshot);
}
