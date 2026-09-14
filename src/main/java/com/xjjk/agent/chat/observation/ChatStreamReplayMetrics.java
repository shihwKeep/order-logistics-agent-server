package com.xjjk.agent.chat.observation;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** 聊天流恢复指标；标签均为固定白名单，不包含用户或请求标识。 */
@Component
public final class ChatStreamReplayMetrics {

    private static final Set<String> MODES = Set.of("resumable", "direct");
    private static final Set<String> RESUME_RESULTS = Set.of(
            "success", "not_found", "rejected", "failure");
    private static final Set<String> EVENT_TYPES = Set.of(
            "session", "heartbeat", "status", "delta", "result", "done", "error");
    private static final Set<String> FAILURES = Set.of(
            "redis", "capacity", "relay", "startup");
    private static final Set<String> CANCEL_RESULTS = Set.of(
            "accepted", "terminal", "failure");

    private final MeterRegistry registry;
    private final AtomicInteger activeRelays = new AtomicInteger();

    public ChatStreamReplayMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "指标注册表不能为空");
        registry.gauge("agent.chat.stream.relay.active", activeRelays);
    }

    public void mode(String mode) {
        registry.counter("agent.chat.stream.mode", "mode", allowed(mode, MODES)).increment();
    }

    public void resume(String result) {
        registry.counter("agent.chat.stream.resume.attempt",
                "result", allowed(result, RESUME_RESULTS)).increment();
    }

    public void replayEvent(String type, int bytes) {
        if (bytes < 0) throw new IllegalArgumentException("事件字节数不能为负数");
        registry.counter("agent.chat.stream.replay.events",
                "type", allowed(type, EVENT_TYPES)).increment();
        registry.summary("agent.chat.stream.replay.bytes").record(bytes);
    }

    public void failure(String reason) {
        registry.counter("agent.chat.stream.replay.failure",
                "reason", allowed(reason, FAILURES)).increment();
    }

    public void cancel(String result) {
        registry.counter("agent.chat.stream.cancel",
                "result", allowed(result, CANCEL_RESULTS)).increment();
    }

    public AutoCloseable relayConnection() {
        activeRelays.incrementAndGet();
        AtomicBoolean closed = new AtomicBoolean();
        return () -> {
            if (closed.compareAndSet(false, true)) {
                activeRelays.decrementAndGet();
            }
        };
    }

    private static String allowed(String value, Set<String> whitelist) {
        if (!whitelist.contains(value)) throw new IllegalArgumentException("未知聊天流指标标签");
        return value;
    }
}
