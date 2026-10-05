package com.xjjk.agent.chat.observation;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** LangGraph4j 复合查询的低基数指标和节点 Observation。 */
@Component
public final class CompositeQueryMetrics {

    public static final String GRAPH = "composite-v2";
    /** Observation 名称与手工 Timer 指标分开，避免 Micrometer 自动 error 标签冲突。 */
    private static final String OBSERVATION_NAME = "agent.composite.node.observation";
    private static final Set<String> NODES = Set.of(
            "input.validate", "branch.dispatch", "business.query", "resolve.latest.order",
            "business.dependent.query", "knowledge.query",
            "result.validate", "answer.compose");
    private static final Set<String> GRAPH_OUTCOMES = Set.of(
            "SUCCESS", "FAILED", "WAITING_INPUT", "NO_RELIABLE_KNOWLEDGE",
            "PARTIAL_SUCCESS", "EXPIRED", "UNAVAILABLE");
    private static final Set<String> NODE_OUTCOMES = Set.of(
            "SUCCESS", "FAILED", "SKIPPED", "ERROR");
    private static final Set<String> RESULT_KINDS = Set.of(
            "order-list", "logistics-timeline", "after-sale-detail",
            "customer-list", "product-list", "knowledge-citations",
            "general-analysis", "external-data-unavailable");
    private static final Set<String> RESULT_OUTCOMES = Set.of(
            "SUCCESS", "FAILURE", "GATE_REJECTED", "MISSING", "SKIPPED");
    private static final Set<String> BRANCHES = Set.of(
            "business:order-list", "business:logistics-timeline",
            "business:logistics-timeline:latest_order",
            "business:customer-list", "business:product-list",
            "business:after-sale-detail", "knowledge:knowledge-citations",
            "general:general-analysis", "general:external-data-unavailable");
    private static final Set<String> CHECKPOINT_OPERATIONS = Set.of(
            "load", "save", "release");
    private static final Set<String> CHECKPOINT_OUTCOMES = Set.of(
        "RESTORED", "MISS", "SUCCESS", "ERROR", "EXPIRED", "UNAVAILABLE");
    private static final Set<String> DEPENDENCY_TYPES = Set.of("LATEST_ORDER");
    private static final Set<String> DEPENDENCY_OUTCOMES = Set.of(
            "WAITING", "RESOLVED", "SKIPPED", "FAILED");
    private static final Set<String> RESUME_OUTCOMES = Set.of("SUCCESS", "FAILED", "EXPIRED");
    private static final Set<String> RETRY_OUTCOMES = Set.of(
            "SCHEDULED", "SUCCESS", "EXHAUSTED", "SKIPPED");
    private static final Set<String> KNOWLEDGE_SKIP_OUTCOMES = Set.of(
            "NO_BUSINESS_MATCH");

    private final MeterRegistry meters;
    private final ObservationRegistry observations;

    public CompositeQueryMetrics(
            MeterRegistry meters,
            ObservationRegistry observations) {
        this.meters = Objects.requireNonNull(meters, "指标注册表不能为空");
        this.observations = Objects.requireNonNull(observations, "观测注册表不能为空");
    }

    public void graph(String outcome) {
        meters.counter("agent.composite.graph", "graph", GRAPH,
                "outcome", bounded(outcome, GRAPH_OUTCOMES, "FAILED"))
                .increment();
    }

    public void result(String kind, String outcome) {
        meters.counter("agent.composite.result",
                "kind", bounded(kind, RESULT_KINDS, "unknown"),
                "outcome", bounded(outcome, RESULT_OUTCOMES, "FAILURE"))
                .increment();
    }

    public void branch(String branch, String outcome) {
        meters.counter("agent.composite.branch",
                "graph", GRAPH,
                "branch", bounded(branch, BRANCHES, "unknown"),
                "outcome", bounded(outcome, GRAPH_OUTCOMES, "FAILED"))
                .increment();
    }

    public void checkpoint(String operation, String outcome) {
        meters.counter("agent.composite.checkpoint",
                "graph", GRAPH,
                "operation", bounded(operation, CHECKPOINT_OPERATIONS, "load"),
                "outcome", bounded(outcome, CHECKPOINT_OUTCOMES, "ERROR"))
                .increment();
    }

    public void retry(String node, String outcome) {
        meters.counter("agent.composite.retry",
                "graph", GRAPH,
                "node", bounded(node, NODES, "unknown"),
                "outcome", bounded(outcome, RETRY_OUTCOMES, "EXHAUSTED"))
                .increment();
    }

    public void dependency(String type, String outcome) {
        meters.counter("agent.composite.dependency",
                "graph", GRAPH,
                "type", bounded(type, DEPENDENCY_TYPES, "LATEST_ORDER"),
                "outcome", bounded(outcome, DEPENDENCY_OUTCOMES, "FAILED"))
                .increment();
    }

    public void partialSuccess() {
        meters.counter("agent.composite.partial_success", "graph", GRAPH).increment();
    }

    public void knowledgeSkip(String outcome) {
        meters.counter("agent.composite.knowledge.skip",
                "graph", GRAPH,
                "outcome", bounded(outcome, KNOWLEDGE_SKIP_OUTCOMES,
                        "NO_BUSINESS_MATCH"))
                .increment();
    }

    public void checkpointResume(String outcome) {
        meters.counter("agent.composite.checkpoint.resume",
                "graph", GRAPH,
                "outcome", bounded(outcome, RESUME_OUTCOMES, "FAILED"))
                .increment();
    }

    public <T> T node(String node, String requestId, Supplier<T> action) {
        Objects.requireNonNull(action, "节点任务不能为空");
        String boundedNode = bounded(node, NODES, "unknown");
        Observation observation = Observation.createNotStarted(
                        OBSERVATION_NAME, observations)
                .contextualName("composite " + boundedNode)
                .lowCardinalityKeyValue("graph", GRAPH)
                .lowCardinalityKeyValue("node", boundedNode)
                .highCardinalityKeyValue("request.id", boundedIdentifier(requestId))
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
            Timer.builder("agent.composite.node")
                    .description("LangGraph4j composite query node duration")
                    .tags("graph", GRAPH, "node", boundedNode, "outcome", outcome)
                    .register(meters)
                    .record(Duration.ofNanos(Math.max(
                            0L, System.nanoTime() - startedAtNanos)));
            observation.stop();
        }
    }

    private String bounded(String value, Set<String> allowed, String fallback) {
        if (value == null) return fallback;
        String normalized = value.toLowerCase(Locale.ROOT);
        for (String candidate : allowed) {
            if (candidate.toLowerCase(Locale.ROOT).equals(normalized)) {
                return candidate;
            }
        }
        return fallback;
    }

    private String boundedIdentifier(String value) {
        if (value == null || value.isBlank()) return "unknown";
        String normalized = value.strip();
        return normalized.length() <= 128 ? normalized : normalized.substring(0, 128);
    }
}
