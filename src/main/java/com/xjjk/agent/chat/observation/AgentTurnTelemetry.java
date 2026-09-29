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
import java.util.function.Supplier;

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
    private static final Set<String> MODEL_RETRY_OUTCOMES = Set.of(
            "SCHEDULED", "EXHAUSTED", "UNKNOWN");
    private static final Set<String> INTENTS = Set.of(
            "EXPLICIT_MEMORY", "ACTION", "DIRECT_BUSINESS", "MEMORY_RECALL",
            "MODEL_REQUIRED", "COMPOSITE", "KNOWLEDGE", "GENERAL", "UNKNOWN");
    private static final Set<String> STAGES = Set.of(
            "turn.prepare", "context.load", "intent.route", "model.stream",
            "composite.query", "result.gate", "turn.finalize");
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

    /**
     * 在根 Agent Observation 下执行一个同步阶段。
     * 未处于 Agent 工作线程时直接执行业务逻辑，保证遥测从不成为业务前置条件。
     */
    public <T> T observeStage(String stage, Supplier<T> action) {
        Objects.requireNonNull(action, "阶段任务不能为空");
        Turn turn = currentTurn.get();
        if (turn == null) return action.get();

        String boundedStage = boundedLower(stage, STAGES, "unknown");
        Observation observation = Observation.createNotStarted(
                        stageObservationName(boundedStage), observations)
                .contextualName(boundedStage.replace('.', ' '))
                .lowCardinalityKeyValue("stage", boundedStage)
                .start();
        long startedAtNanos = System.nanoTime();
        String outcome = "SUCCESS";
        try (Observation.Scope ignored = observation.openScope()) {
            return action.get();
        } catch (RuntimeException | Error exception) {
            outcome = "ERROR";
            observation.error(exception);
            throw exception;
        } finally {
            observation.lowCardinalityKeyValue("outcome", outcome);
            Timer.builder("agent.turn.stage.duration")
                    .description("Agent turn stage duration")
                    .tags("stage", boundedStage, "outcome", outcome)
                    .register(meters)
                    .record(Duration.ofNanos(Math.max(
                            0L, System.nanoTime() - startedAtNanos)));
            observation.stop();
        }
    }

    public void observeStage(String stage, Runnable action) {
        observeStage(stage, () -> {
            action.run();
            return null;
        });
    }

    /** 把供应商用量快照转换成无用户标识、低基数的模型指标。 */
    public void recordModelMetrics(ChatCallMetrics metrics) {
        if (metrics == null) return;
        recordTokens("input", metrics.inputTokens());
        recordTokens("output", metrics.outputTokens());
        recordTokens("total", metrics.totalTokens());

        String family = modelFamily(metrics.responseModel());
        if (metrics.firstDeltaLatencyMs() != null
                && metrics.firstDeltaLatencyMs() >= 0) {
            Timer.builder("agent.model.first.delta")
                    .description("Latency until the first model delta is sent")
                    .tag("model_family", family)
                    .register(meters)
                    .record(Duration.ofMillis(metrics.firstDeltaLatencyMs()));
        }
        meters.counter(
                "agent.model.completion",
                "model_family", family,
                "outcome", boundedUpper(metrics.status(), OUTCOMES, "UNKNOWN"),
                "finish_reason", finishReason(metrics.finishReason()))
                .increment();
    }

    /** 记录模型流重试，不把 requestId 或异常全文放进 Prometheus 标签。 */
    public void recordModelRetry(String outcome) {
        meters.counter(
                "agent.model.stream.retry",
                "outcome", boundedUpper(outcome, MODEL_RETRY_OUTCOMES, "UNKNOWN"))
                .increment();
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

    private void recordTokens(String direction, Long count) {
        if (count == null || count < 0) return;
        meters.summary("agent.model.tokens", "direction", direction).record(count);
    }

    private static String modelFamily(String model) {
        if (!StringUtils.hasText(model)) return "unknown";
        String normalized = model.toLowerCase(Locale.ROOT);
        if (normalized.contains("qwen")) return "qwen";
        if (normalized.contains("deepseek")) return "deepseek";
        if (normalized.contains("gpt")) return "gpt";
        return "other";
    }

    private static String finishReason(String reason) {
        if (!StringUtils.hasText(reason)) return "NONE";
        String normalized = reason.toUpperCase(Locale.ROOT);
        if ("STOP".equals(normalized)) return "STOP";
        if ("LENGTH".equals(normalized)) return "LENGTH";
        if (normalized.contains("TOOL")) return "TOOL_CALLS";
        if (normalized.startsWith("MEMORY_")) return "MEMORY";
        if (normalized.startsWith("ACTION_")) return "ACTION";
        return "OTHER";
    }

    private static String stageObservationName(String stage) {
        return stage.startsWith("turn.")
                ? "agent." + stage : "agent.turn." + stage;
    }
}
