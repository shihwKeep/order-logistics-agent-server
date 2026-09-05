package com.xjjk.agent.chat.cache;

import com.xjjk.agent.chat.domain.memory.ChatHistorySnapshot;
import com.xjjk.agent.chat.domain.memory.ChatHistoryTurn;

import java.util.List;
import java.util.Objects;

/**
 * Redis 中的会话历史快照传输结构。
 *
 * 显式维护结构版本，不直接序列化数据库实体或 Spring AI 消息对象。
 */
public record CachedChatHistorySnapshot(
        int schemaVersion,
        long tenantId,
        long userId,
        String conversationId,
        long memoryVersion,
        long memoryUntilSequence,
        long beforeSequence,
        List<CachedChatHistoryTurn> turns,
        boolean hasEarlierMessages,
        boolean readBudgetTruncated
) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public CachedChatHistorySnapshot {
        Objects.requireNonNull(turns, "缓存历史轮次不能为空");
        turns = List.copyOf(turns);
    }

    public static CachedChatHistorySnapshot fromDomain(
            ChatHistorySnapshot snapshot
    ) {
        Objects.requireNonNull(snapshot, "历史快照不能为空");

        List<CachedChatHistoryTurn> cachedTurns = snapshot.turns()
                .stream()
                .map(CachedChatHistoryTurn::fromDomain)
                .toList();

        return new CachedChatHistorySnapshot(
                CURRENT_SCHEMA_VERSION,
                snapshot.tenantId(),
                snapshot.userId(),
                snapshot.conversationId(),
                snapshot.memoryVersion(),
                snapshot.memoryUntilSequence(),
                snapshot.beforeSequence(),
                cachedTurns,
                snapshot.hasEarlierMessages(),
                snapshot.readBudgetTruncated()
        );
    }

    public ChatHistorySnapshot toDomain() {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("缓存历史结构版本不受支持");
        }

        List<ChatHistoryTurn> historyTurns = turns.stream()
                .map(CachedChatHistoryTurn::toDomain)
                .toList();

        return new ChatHistorySnapshot(
                tenantId,
                userId,
                conversationId,
                memoryVersion,
                memoryUntilSequence,
                beforeSequence,
                historyTurns,
                hasEarlierMessages,
                readBudgetTruncated
        );
    }

    /**
     * 缓存对象进入日志时只输出元信息，避免泄露问答正文。
     */
    @Override
    public String toString() {
        return "CachedChatHistorySnapshot[schemaVersion=" + schemaVersion
                + ", tenantId=" + tenantId
                + ", userId=" + userId
                + ", conversationId=" + conversationId
                + ", memoryVersion=" + memoryVersion
                + ", memoryUntilSequence=" + memoryUntilSequence
                + ", beforeSequence=" + beforeSequence
                + ", turnCount=" + turns.size()
                + ", hasEarlierMessages=" + hasEarlierMessages
                + ", readBudgetTruncated=" + readBudgetTruncated
                + "]";
    }

    /**
     * Redis DTO 中的一轮完整问答。
     */
    public record CachedChatHistoryTurn(
            String requestId,
            long userSequence,
            long assistantSequence,
            String userContent,
            String assistantContent
    ) {

        private static CachedChatHistoryTurn fromDomain(
                ChatHistoryTurn turn
        ) {
            return new CachedChatHistoryTurn(
                    turn.requestId(),
                    turn.userSequence(),
                    turn.assistantSequence(),
                    turn.userContent(),
                    turn.assistantContent()
            );
        }

        private ChatHistoryTurn toDomain() {
            return new ChatHistoryTurn(
                    requestId,
                    userSequence,
                    assistantSequence,
                    userContent,
                    assistantContent
            );
        }

        @Override
        public String toString() {
            return "CachedChatHistoryTurn[requestId=" + requestId
                    + ", userSequence=" + userSequence
                    + ", assistantSequence=" + assistantSequence
                    + ", content=<redacted>]";
        }
    }
}
