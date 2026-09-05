package com.xjjk.agent.chat.cache;

import com.xjjk.agent.chat.config.ChatHistoryCacheProperties;
import com.xjjk.agent.chat.config.ChatHistoryProperties;
import com.xjjk.agent.chat.domain.memory.ChatHistoryCursor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 会话历史快照缓存 Key 工厂。
 *
 * Key 同时隔离身份、稳定历史版本和读取策略。
 */
@Component
@RequiredArgsConstructor
public class ChatHistoryCacheKeyFactory {

    private final ChatHistoryCacheProperties cacheProperties;
    private final ChatHistoryProperties historyProperties;

    public String create(ChatHistoryCursor cursor) {
        Objects.requireNonNull(cursor, "稳定历史游标不能为空");

        return cacheProperties.keyPrefix()
                + ":" + cursor.tenantId()
                + ":" + cursor.userId()
                + ":" + cursor.conversationId()
                + ":" + cursor.memoryVersion()
                + ":" + cursor.memoryUntilSequence()
                + ":m" + historyProperties.maxScanMessages()
                + "-b" + historyProperties.maxReadBytes();
    }
}
