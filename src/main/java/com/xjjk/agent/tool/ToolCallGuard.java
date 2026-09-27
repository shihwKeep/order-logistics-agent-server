package com.xjjk.agent.tool;

import com.xjjk.agent.tool.observation.ToolCallMetrics;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 单轮工具调用的并发去重和不同调用键数量限制。
 *
 * <p>Guard 必须随每一轮对话创建，不能作为 Spring 单例共享，否则会把不同请求
 * 的调用结果和额度错误地混在一起。</p>
 */
public final class ToolCallGuard {

    private final int maxDistinctCalls;
    private final AtomicInteger distinctCallCount = new AtomicInteger();
    private final ConcurrentHashMap<String, CompletableFuture<String>> calls =
            new ConcurrentHashMap<>();
    private final Object admissionLock = new Object();
    private final ToolCallMetrics metrics;

    public ToolCallGuard(int maxDistinctCalls) {
        this(maxDistinctCalls, null);
    }

    public ToolCallGuard(int maxDistinctCalls, ToolCallMetrics metrics) {
        if (maxDistinctCalls <= 0) {
            throw new IllegalArgumentException("工具调用上限必须大于0");
        }
        this.maxDistinctCalls = maxDistinctCalls;
        this.metrics = metrics;
    }

    /**
     * 执行一次规范化后的工具调用。
     *
     * <p>Map 中保存的是首次调用的 Future，而不是只保存最终字符串。这样同一个键
     * 即使并发到达，也只会有一个线程真正访问下游，其他线程等待并复用同一结果。</p>
     */
    public String execute(
            String toolName,
            String canonicalArguments,
            Supplier<String> action
    ) {
        Objects.requireNonNull(toolName, "工具名称不能为空");
        Objects.requireNonNull(canonicalArguments, "规范化参数不能为空");
        Objects.requireNonNull(action, "工具调用不能为空");

        String callKey = toolName + '\n' + canonicalArguments;
        CompletableFuture<String> candidate = new CompletableFuture<>();
        CompletableFuture<String> existing;
        boolean limitExceeded = false;
        // 新键登记与额度变化使用同一把短锁。工具调用和 Future 等待均在锁外执行，
        // 因而不会串行化真实下游请求，只保证“开放重试”和额度归还不可交错。
        synchronized (admissionLock) {
            existing = calls.putIfAbsent(callKey, candidate);
            if (existing == null) {
                limitExceeded = distinctCallCount.incrementAndGet() > maxDistinctCalls;
            }
        }
        if (existing != null) {
            recordGuard(toolName, "REUSED");
            return await(existing);
        }

        if (limitExceeded) {
            recordGuard(toolName, "LIMIT_EXCEEDED");
            ToolCallLimitExceededException exception =
                    new ToolCallLimitExceededException(maxDistinctCalls);
            completeFailedCall(callKey, candidate, exception);
            throw exception;
        }

        recordGuard(toolName, "FIRST");
        try {
            String result = metrics == null
                    ? action.get()
                    : metrics.execute(toolName, action);
            // 成功 Future 保留到本轮结束，保证重复调用既不访问下游，也不重复发布 SSE。
            candidate.complete(result);
            return result;
        } catch (RuntimeException | Error exception) {
            // 失败键不应永久占用调用额度；移除后调用方可按明确策略重新尝试。
            recordGuard(toolName, "FAILED_RELEASED");
            completeFailedCall(callKey, candidate, exception);
            throw exception;
        }
    }

    private void recordGuard(String toolName, String decision) {
        if (metrics != null) {
            metrics.guard(toolName, decision);
        }
    }

    private void completeFailedCall(
            String callKey,
            CompletableFuture<String> candidate,
            Throwable exception
    ) {
        // Future 是失败调用组的线性化点：先让已经附着的等待者全部观察同一失败，
        // 再原子地删除该键并归还额度。删除前到达的调用仍属于旧失败组；
        // 删除完成后的调用才允许登记为新的首次执行者。
        candidate.completeExceptionally(exception);
        synchronized (admissionLock) {
            if (calls.remove(callKey, candidate)) {
                distinctCallCount.decrementAndGet();
            }
        }
    }

    private String await(CompletableFuture<String> existing) {
        try {
            return existing.join();
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("工具调用执行失败", cause);
        }
    }
}
