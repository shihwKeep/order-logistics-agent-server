package com.xjjk.agent.chat.service.stream;

import com.xjjk.agent.chat.replay.ChatReplayRepository;
import com.xjjk.agent.identity.domain.AgentIdentity;

import java.time.Duration;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** 对跨实例取消标志做短周期缓存，避免模型每个分片都访问 Redis。 */
public final class ChatReplayCancellationProbe implements BooleanSupplier {

    private final ChatReplayRepository repository;
    private final AgentIdentity identity;
    private final String requestId;
    private final long intervalNanos;
    private final LongSupplier ticker;
    private boolean initialized;
    private boolean cancelled;
    private long lastCheckNanos;

    public ChatReplayCancellationProbe(
            ChatReplayRepository repository,
            AgentIdentity identity,
            String requestId,
            Duration interval
    ) {
        this(repository, identity, requestId, interval, System::nanoTime);
    }

    ChatReplayCancellationProbe(
            ChatReplayRepository repository,
            AgentIdentity identity,
            String requestId,
            Duration interval,
            LongSupplier ticker
    ) {
        this.repository = Objects.requireNonNull(repository, "回放仓储不能为空");
        this.identity = Objects.requireNonNull(identity, "认证身份不能为空");
        this.requestId = Objects.requireNonNull(requestId, "请求 ID 不能为空");
        Objects.requireNonNull(interval, "取消探针间隔不能为空");
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("取消探针间隔必须大于零");
        }
        this.intervalNanos = interval.toNanos();
        this.ticker = Objects.requireNonNull(ticker, "单调时钟不能为空");
    }

    @Override
    public synchronized boolean getAsBoolean() {
        if (cancelled) {
            return true;
        }
        long now = ticker.getAsLong();
        if (initialized && now - lastCheckNanos < intervalNanos) {
            return false;
        }
        initialized = true;
        lastCheckNanos = now;
        cancelled = repository.cancellationRequested(identity, requestId);
        return cancelled;
    }
}
