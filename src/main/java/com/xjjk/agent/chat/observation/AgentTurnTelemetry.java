package com.xjjk.agent.chat.observation;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单轮 Agent 后台任务的根遥测。
 *
 * <p>Prometheus 只接收经过白名单约束的低基数标签；requestId、conversationId
 * 只进入 Trace 高基数属性，禁止作为指标标签使用。</p>
 */
@Component
public final class AgentTurnTelemetry {

    private static final Set<String> MODES = Set.of("resumable", "direct");
    private static final Set<String> OUTCOMES = Set.of(
            "SUCCESS", "FAILED", "TIMEOUT", "CANCELLED", "OUTPUT_ERROR",
            "OUTPUT_LIMIT", "EMPTY_RESPONSE", "INCOMPLETE", "REJECTED",
            "PERSISTENCE_FAILED", "STALE_REQUEST", "UNKNOWN");
    private static final Set<String> INTENTS = Set.of(
            "EXPLICIT_MEMORY", "ACTION", "DIRECT_BUSINESS", "MEMORY_RECALL",
            "MODEL_REQUIRED", "KNOWLEDGE", "GENERAL", "UNKNOWN");
    private static final int MAX_TRACE_IDENTIFIER_LENGTH = 128;

    private final MeterRegistry meters;
    private final ObservationRegistry observations;
    private final ThreadLocal<Turn> currentTurn = new ThreadLocal<>();

    public AgentTurnTelemetry(
            MeterRegistry meters,
            ObservationRegistry observations
    ) {
        this.meters = Objects.requireNonNull(meters, "指标注册表不能为空");
        this.observations = Objects.requireNonNull(observations, "观测注册表不能为空");
    }

    /** 启动一轮后台 Agent 任务；网络重连不得再次调用本方法。 */
    public Turn start(
            String mode,
            String requestId,
            String conversationId
    ) {
        String boundedMode = boundedLower(mode, MODES, "unknown");
        Observation observation = Observation.createNotStarted("agent.turn", observations)
                .contextualName("agent turn")
                .lowCardinalityKeyValue("mode", boundedMode)
                .highCardinalityKeyValue("request.id", traceIdentifier(requestId));
        if (StringUtils.hasText(conversationId)) {
            observation.highCardinalityKeyValue(
                    "conversation.id", traceIdentifier(conversationId));
        }
        return new Turn(meters, observation.start(), boundedMode);
    }

    /**
     * 在实际后台工作线程中执行一轮任务并绑定根 Observation。
     * 绑定范围严格限制在 Runnable 生命周期内，线程池复用线程时不会串到下一轮请求。
     */
    public void run(
            String mode,
            String requestId,
            String conversationId,
            Runnable action
    ) {
        Objects.requireNonNull(action, "Agent 任务不能为空");
        Turn previous = currentTurn.get();
        try (Turn turn = start(mode, requestId, conversationId)) {
            currentTurn.set(turn);
            try (Observation.Scope ignored = turn.openScope()) {
                action.run();
            }
        } finally {
            if (previous == null) {
                currentTurn.remove();
            } else {
                currentTurn.set(previous);
            }
        }
    }

    /** 由同一后台线程的统一收尾器补全最终状态和正式会话号。 */
    public void completeCurrent(
            String outcome,
            String intent,
            String conversationId
    ) {
        Turn turn = currentTurn.get();
        if (turn == null) return;
        turn.conversationId(conversationId);
        turn.complete(outcome, intent);
    }

    boolean hasCurrentTurn() {
        return currentTurn.get() != null;
    }

    public static final class Turn implements AutoCloseable {
        private final MeterRegistry meters;
        private final Observation observation;
        private final String mode;
        private final long startedAtNanos = System.nanoTime();
        private final AtomicBoolean completed = new AtomicBoolean();

        private Turn(
                MeterRegistry meters,
                Observation observation,
                String mode
        ) {
            this.meters = meters;
            this.observation = observation;
            this.mode = mode;
        }

        /** 让同一工作线程内创建的阶段 Observation 自动成为 agent.turn 的子节点。 */
        public Observation.Scope openScope() {
            return observation.openScope();
        }

        /** 会话在准备事务后才生成时，可在结束前补入 Trace；仍不会成为指标标签。 */
        public void conversationId(String conversationId) {
            if (StringUtils.hasText(conversationId) && !completed.get()) {
                observation.highCardinalityKeyValue(
                        "conversation.id", traceIdentifier(conversationId));
            }
        }

        /** 幂等结束：重复收尾或 close 不会重复计数、重复停止 Span。 */
        public void complete(String outcome, String intent) {
            if (!completed.compareAndSet(false, true)) {
                return;
            }
            String boundedOutcome = boundedUpper(outcome, OUTCOMES, "UNKNOWN");
            String boundedIntent = boundedUpper(intent, INTENTS, "UNKNOWN");
            observation.lowCardinalityKeyValue("outcome", boundedOutcome);
            observation.lowCardinalityKeyValue("intent", boundedIntent);

            meters.counter(
                    "agent.turn.completed",
                    "mode", mode,
                    "outcome", boundedOutcome,
                    "intent", boundedIntent).increment();
            Timer.builder("agent.turn.duration")
                    .description("Agent turn background task duration")
                    .tags(
                            "mode", mode,
                            "outcome", boundedOutcome,
                            "intent", boundedIntent)
                    .register(meters)
                    .record(Duration.ofNanos(Math.max(
                            0L, System.nanoTime() - startedAtNanos)));
            observation.stop();
        }

        @Override
        public void close() {
            complete("UNKNOWN", "UNKNOWN");
        }
    }

    private static String boundedLower(
            String value,
            Set<String> allowed,
            String fallback
    ) {
        if (value == null) return fallback;
        String normalized = value.toLowerCase(Locale.ROOT);
        return allowed.contains(normalized) ? normalized : fallback;
    }

    private static String boundedUpper(
            String value,
            Set<String> allowed,
            String fallback
    ) {
        if (value == null) return fallback;
        String normalized = value.toUpperCase(Locale.ROOT);
        return allowed.contains(normalized) ? normalized : fallback;
    }

    private static String traceIdentifier(String value) {
        if (!StringUtils.hasText(value)) return "unknown";
        String trimmed = value.trim();
        return trimmed.length() <= MAX_TRACE_IDENTIFIER_LENGTH
                ? trimmed : trimmed.substring(0, MAX_TRACE_IDENTIFIER_LENGTH);
    }
}
