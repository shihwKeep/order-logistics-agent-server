package com.xjjk.agent.tool;

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

    public ToolCallGuard(int maxDistinctCalls) {
        if (maxDistinctCalls <= 0) {
            throw new IllegalArgumentException("工具调用上限必须大于0");
        }
        this.maxDistinctCalls = maxDistinctCalls;
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
        CompletableFuture<String> existing = calls.putIfAbsent(callKey, candidate);
        if (existing != null) {
            return await(existing);
        }

        // 只有成功占据新键的线程才计数；超过上限的键不会留在缓存中。
        int currentCount = distinctCallCount.incrementAndGet();
        if (currentCount > maxDistinctCalls) {
            ToolCallLimitExceededException exception =
                    new ToolCallLimitExceededException(maxDistinctCalls);
            removeFailedCall(callKey, candidate);
            candidate.completeExceptionally(exception);
            throw exception;
        }

        try {
            String result = action.get();
            // 成功 Future 保留到本轮结束，保证重复调用既不访问下游，也不重复发布 SSE。
            candidate.complete(result);
            return result;
        } catch (RuntimeException | Error exception) {
            // 失败键不应永久占用调用额度；移除后调用方可按明确策略重新尝试。
            removeFailedCall(callKey, candidate);
            candidate.completeExceptionally(exception);
            throw exception;
        }
    }

    private void removeFailedCall(
            String callKey,
            CompletableFuture<String> candidate
    ) {
        // 先归还额度、再移除键；并发重复调用在移除前仍共享本次失败，
        // 移除完成后的明确重试才会成为新的首次执行者。
        distinctCallCount.decrementAndGet();
        calls.remove(callKey, candidate);
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
