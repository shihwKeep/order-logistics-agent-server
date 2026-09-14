package com.xjjk.agent.chat.service.stream;

import com.xjjk.agent.identity.domain.AgentIdentity;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 本实例正在运行的聊天任务索引，仅用于快速查找和立即取消。
 * 跨实例取消与最终正确性由 Redis control 和 MySQL 条件更新保证。
 */
@Component
public final class ChatTurnJobRegistry {

    private final ConcurrentMap<String, ChatTurnJob> jobs = new ConcurrentHashMap<>();

    public boolean register(ChatTurnJob job) {
        ChatTurnJob value = Objects.requireNonNull(job, "聊天任务不能为空");
        return jobs.putIfAbsent(value.requestId(), value) == null;
    }

    public Optional<ChatTurnJob> find(String requestId) {
        return Optional.ofNullable(jobs.get(requestId));
    }

    public boolean cancel(AgentIdentity identity, String requestId) {
        ChatTurnJob job = jobs.get(requestId);
        return job != null && job.ownedBy(identity) && job.cancel();
    }

    public void remove(String requestId) {
        ChatTurnJob removed = jobs.remove(requestId);
        if (removed != null) {
            removed.close();
        }
    }

    /** 仅删除调用方持有的同一任务，避免旧任务收尾误删复用键的新任务。 */
    public boolean remove(String requestId, ChatTurnJob expected) {
        boolean removed = jobs.remove(requestId, expected);
        if (removed) {
            expected.close();
        }
        return removed;
    }
}
