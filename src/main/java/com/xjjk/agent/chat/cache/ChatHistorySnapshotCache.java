package com.xjjk.agent.chat.cache;

import com.xjjk.agent.chat.domain.memory.ChatHistoryCursor;
import com.xjjk.agent.chat.domain.memory.ChatHistorySnapshot;

import java.util.Optional;

/**
 * 稳定会话历史快照缓存端口。
 */
public interface ChatHistorySnapshotCache {

    /**
     * 查询稳定历史快照。缓存关闭、未命中或故障都返回空，由调用方回源 MySQL。
     */
    Optional<ChatHistorySnapshot> get(ChatHistoryCursor cursor);

    /**
     * 尽力写入稳定历史快照。写入失败不能反向影响已经成功的聊天主流程。
     */
    void put(ChatHistoryCursor cursor, ChatHistorySnapshot snapshot);
}
