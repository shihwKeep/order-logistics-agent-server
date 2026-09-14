package com.xjjk.agent.chat.service.stream;

import com.xjjk.agent.chat.stream.ChatStreamControl;
import com.xjjk.agent.identity.domain.AgentIdentity;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.FutureTask;

/**
 * 一轮与 SSE 连接解耦的 Agent 生产任务。
 *
 * <p>网络连接只是该任务事件流的消费者；连接离开不改变任务状态。</p>
 */
public final class ChatTurnJob implements AutoCloseable {

    private final String requestId;
    private final AgentIdentity owner;
    private final ChatStreamControl control;
    private final FutureTask<?> task;
    private final Instant expiresAt;
    private AutoCloseable heartbeatLease;
    private boolean closed;

    public ChatTurnJob(
            String requestId,
            AgentIdentity owner,
            ChatStreamControl control,
            FutureTask<?> task,
            Instant expiresAt
    ) {
        this.requestId = requireText(requestId, "请求 ID 不能为空");
        this.owner = Objects.requireNonNull(owner, "任务归属不能为空");
        this.control = Objects.requireNonNull(control, "任务控制对象不能为空");
        this.task = Objects.requireNonNull(task, "异步任务不能为空");
        this.expiresAt = Objects.requireNonNull(expiresAt, "任务截止时间不能为空");
        control.bind(task);
    }

    public String requestId() {
        return requestId;
    }

    public AgentIdentity owner() {
        return owner;
    }

    public ChatStreamControl control() {
        return control;
    }

    public FutureTask<?> task() {
        return task;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    /** SSE 中继断开只结束消费者，不得取消仍在运行的生产任务。 */
    public void relayDisconnected() {
        // 语义明确的空操作，防止调用方把网络生命周期误接到任务取消。
    }

    /** 心跳属于生产任务；连接重连时不重复创建，也不随单条连接关闭。 */
    public synchronized void bindHeartbeat(AutoCloseable lease) {
        AutoCloseable value = Objects.requireNonNull(lease, "心跳租约不能为空");
        if (heartbeatLease != null) {
            throw new IllegalStateException("心跳租约不能重复绑定");
        }
        heartbeatLease = value;
        if (closed) {
            closeLease(value);
        }
    }

    public boolean cancel() {
        return control.requestStop(com.xjjk.agent.chat.domain.MessageStatus.CANCELLED);
    }

    public boolean ownedBy(AgentIdentity identity) {
        return identity != null
                && owner.tenantId() == identity.tenantId()
                && owner.userId() == identity.userId();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (heartbeatLease != null) {
            closeLease(heartbeatLease);
        }
    }

    private void closeLease(AutoCloseable lease) {
        try {
            lease.close();
        } catch (Exception exception) {
            throw new IllegalStateException("关闭聊天任务心跳失败", exception);
        }
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
