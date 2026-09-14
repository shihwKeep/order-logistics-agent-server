package com.xjjk.agent.chat.replay;

import com.xjjk.agent.identity.domain.AgentIdentity;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** 可恢复聊天任务的短期事件日志端口。 */
public interface ChatReplayRepository {
    boolean available();
    ChatReplayCreateResult create(ChatReplayMetadata metadata);
    boolean bindConversation(AgentIdentity identity, String requestId, String conversationId);
    ChatReplayEvent append(AgentIdentity identity, String requestId, String type, Object payload);
    List<ChatReplayEvent> readAfter(AgentIdentity identity, String requestId,
                                    long afterSequence, Duration blockTimeout);
    Optional<ChatReplaySnapshot> status(AgentIdentity identity, String requestId);
    boolean activateConnection(AgentIdentity identity, String requestId, String connectionId);
    boolean isActiveConnection(AgentIdentity identity, String requestId, String connectionId);
    boolean requestCancel(AgentIdentity identity, String requestId);
    boolean cancellationRequested(AgentIdentity identity, String requestId);
}
