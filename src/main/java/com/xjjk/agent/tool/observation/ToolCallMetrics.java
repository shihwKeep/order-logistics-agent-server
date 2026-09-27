package com.xjjk.agent.tool.observation;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** 工具执行与单轮保护器指标；工具名和结果均受服务端白名单约束。 */
@Component
public final class ToolCallMetrics {

    private static final Set<String> TOOLS = Set.of(
            "search_orders", "get_order_logistics", "search_after_sales",
            "get_after_sale_detail", "search_customers", "list_customer_orders",
            "search_knowledge", "search_products");
    private static final Set<String> GUARD_DECISIONS = Set.of(
            "FIRST", "REUSED", "LIMIT_EXCEEDED", "FAILED_RELEASED");
    private static final Set<String> RESULT_KINDS = Set.of(
            "order-list", "logistics-timeline", "after-sale-list",
            "after-sale-detail", "customer-list", "product-list",
            "knowledge-citations");
    private static final Set<String> RESULT_OUTCOMES = Set.of(
            "STAGED", "PUBLISHED", "PROTOCOL_REJECTED", "GATE_REJECTED");

    private final MeterRegistry meters;
    private final ObservationRegistry observations;

    public ToolCallMetrics(
            MeterRegistry meters,
            ObservationRegistry observations
    ) {
        this.meters = Objects.requireNonNull(meters, "指标注册表不能为空");
        this.observations = Objects.requireNonNull(observations, "观测注册表不能为空");
    }

    /** 只包裹真正访问下游的首次执行；复用等待不会创建伪造的下游 Span。 */
    public <T> T execute(String toolName, Supplier<T> action) {
        Objects.requireNonNull(action, "工具执行不能为空");
        String tool = tool(toolName);
        Observation observation = Observation.createNotStarted(
                        "agent.tool.call", observations)
                .contextualName("agent tool " + tool)
                .lowCardinalityKeyValue("tool", tool)
                .start();
        long startedAtNanos = System.nanoTime();
        String outcome = "SUCCESS";
        try (Observation.Scope ignored = observation.openScope()) {
            return action.get();
        } catch (RuntimeException | Error exception) {
            outcome = "FAILURE";
            observation.error(exception);
            throw exception;
        } finally {
            observation.lowCardinalityKeyValue("outcome", outcome);
            Timer.builder("agent.tool.call.duration")
                    .description("Real downstream tool execution duration")
                    .tags("tool", tool, "outcome", outcome)
                    .register(meters)
                    .record(Duration.ofNanos(Math.max(
                            0L, System.nanoTime() - startedAtNanos)));
            observation.stop();
        }
    }

    public void guard(String toolName, String decision) {
        String boundedDecision = GUARD_DECISIONS.contains(decision)
                ? decision : "UNKNOWN";
        meters.counter(
                "agent.tool.guard",
                "tool", tool(toolName),
                "decision", boundedDecision).increment();
    }

    /** 记录结构化卡片从协议校验、暂存、结果门禁到真正发布的状态变化。 */
    public void result(String kind, String outcome) {
        String boundedKind = RESULT_KINDS.contains(kind) ? kind : "unknown";
        String boundedOutcome = RESULT_OUTCOMES.contains(outcome)
                ? outcome : "UNKNOWN";
        meters.counter(
                "agent.tool.result",
                "kind", boundedKind,
                "outcome", boundedOutcome).increment();
    }

    private String tool(String toolName) {
        return TOOLS.contains(toolName) ? toolName : "unknown";
    }
}
