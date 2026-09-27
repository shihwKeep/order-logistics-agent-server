package com.xjjk.agent.integration.observation;

import feign.FeignException;
import feign.RetryableException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.stereotype.Component;

import java.net.ConnectException;
import java.net.ProtocolException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 下游 HTTP 调用的统一低基数观测边界。
 *
 * <p>这里只记录服务、操作、尝试次数和有限的结果分类，不记录请求正文、业务编号、
 * Token 或供应商异常正文。Gateway 仍负责原有的重试、熔断、协议校验和异常映射，
 * 观测组件只旁路记录，不改变业务异常语义。</p>
 */
@Component
public final class DownstreamCallMetrics {

    private static final Set<String> SERVICES = Set.of(
            "order", "customer", "after_sale", "product", "knowledge");
    private static final Set<String> OPERATIONS = Set.of(
            "search", "search_by_customer", "logistics", "detail", "retrieve");
    private static final Set<String> OUTCOMES = Set.of(
            "SUCCESS", "REMOTE_REJECTED", "SERVER_ERROR", "CONNECT_FAILURE",
            "READ_TIMEOUT", "PROTOCOL_ERROR", "CIRCUIT_OPEN", "CLIENT_ERROR", "UNKNOWN");

    private final MeterRegistry meters;
    private final ObservationRegistry observations;

    public DownstreamCallMetrics(
            MeterRegistry meters,
            ObservationRegistry observations) {
        this.meters = Objects.requireNonNull(meters, "指标注册表不能为空");
        this.observations = Objects.requireNonNull(observations, "观测注册表不能为空");
    }

    /** 包裹一次逻辑下游调用；重试次数由 Gateway 单独按 attempt 记录。 */
    public <T> T observe(String service, String operation, Supplier<T> action) {
        Objects.requireNonNull(action, "下游调用不能为空");
        String boundedService = service(service);
        String boundedOperation = operation(operation);
        Observation observation = Observation.createNotStarted(
                        "agent.downstream.call", observations)
                .contextualName("downstream " + boundedService + " " + boundedOperation)
                .lowCardinalityKeyValue("service", boundedService)
                .lowCardinalityKeyValue("operation", boundedOperation)
                .start();
        long startedAt = System.nanoTime();
        String outcome = "SUCCESS";
        try (Observation.Scope ignored = observation.openScope()) {
            return action.get();
        } catch (RuntimeException | Error exception) {
            outcome = classify(exception);
            observation.error(exception);
            throw exception;
        } finally {
            observation.lowCardinalityKeyValue("outcome", outcome);
            Timer.builder("agent.downstream.call.duration")
                    .description("Logical downstream call duration")
                    .tags("service", boundedService,
                            "operation", boundedOperation,
                            "outcome", outcome)
                    .register(meters)
                    .record(Duration.ofNanos(Math.max(
                            0L, System.nanoTime() - startedAt)));
            observation.stop();
        }
    }

    /** 记录 Gateway 每次真实尝试，以及该次尝试是否会继续重试。 */
    public void attempt(
            String service,
            String operation,
            int attempt,
            Throwable failure,
            boolean retry) {
        String boundedAttempt = attempt > 0 && attempt <= 3
                ? Integer.toString(attempt) : "unknown";
        meters.counter(
                "agent.downstream.attempt",
                "service", service(service),
                "operation", operation(operation),
                "attempt", boundedAttempt,
                "outcome", classify(failure),
                "retry", Boolean.toString(retry)).increment();
    }

    public String classify(Throwable failure) {
        if (failure == null) {
            return "SUCCESS";
        }
        if (containsSimpleName(failure, "CallNotPermittedException")) {
            return "CIRCUIT_OPEN";
        }
        if (failure instanceof FeignException feign) {
            int status = feign.status();
            if (status == 401 || status == 403 || status == 404 || status == 409
                    || status == 422) {
                return "REMOTE_REJECTED";
            }
            if (status >= 500 && status <= 599) {
                return "SERVER_ERROR";
            }
        }
        if (contains(failure, SocketTimeoutException.class)) {
            return "READ_TIMEOUT";
        }
        if (contains(failure, ConnectException.class)
                || contains(failure, UnknownHostException.class)) {
            return "CONNECT_FAILURE";
        }
        if (contains(failure, ProtocolException.class)) {
            return "PROTOCOL_ERROR";
        }
        return "CLIENT_ERROR";
    }

    private boolean contains(Throwable failure, Class<? extends Throwable> type) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsSimpleName(Throwable failure, String typeName) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (typeName.equals(current.getClass().getSimpleName())) {
                return true;
            }
        }
        return false;
    }

    private String service(String value) {
        return SERVICES.contains(value) ? value : "unknown";
    }

    private String operation(String value) {
        return OPERATIONS.contains(value) ? value : "unknown";
    }
}
