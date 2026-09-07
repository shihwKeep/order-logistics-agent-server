package com.xjjk.agent.tool;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolCallGuardTest {

    @Test
    void executesSameCanonicalCallOnceAndLimitsDistinctCalls() {
        ToolCallGuard guard = new ToolCallGuard(3);
        AtomicInteger executions = new AtomicInteger();

        String first = guard.execute("search_orders", "AUTO|O1", () -> {
            executions.incrementAndGet();
            return "result-1";
        });
        String duplicate = guard.execute("search_orders", "AUTO|O1", () -> {
            executions.incrementAndGet();
            return "unexpected";
        });
        guard.execute("search_orders", "AUTO|O2", () -> "result-2");
        guard.execute("get_order_logistics", "ORDER_CODE|O1", () -> "result-3");

        assertThat(first).isEqualTo("result-1");
        assertThat(duplicate).isEqualTo("result-1");
        assertThat(executions).hasValue(1);
        assertThatThrownBy(() ->
                guard.execute("search_products", "fish|1", () -> "fourth"))
                .isInstanceOf(ToolCallLimitExceededException.class);
    }

    @Test
    void failedCallCanBeRetriedWithoutConsumingDistinctCallQuota() {
        ToolCallGuard guard = new ToolCallGuard(1);
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> guard.execute("search_orders", "AUTO|O1", () -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("temporary failure");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("temporary failure");

        assertThat(guard.execute("search_orders", "AUTO|O1", () -> {
            attempts.incrementAndGet();
            return "recovered";
        })).isEqualTo("recovered");
        assertThat(attempts).hasValue(2);

        assertThatThrownBy(() ->
                guard.execute("search_products", "fish|1", () -> "second key"))
                .isInstanceOf(ToolCallLimitExceededException.class);
    }

    @Test
    void concurrentCallsWithSameCanonicalKeyShareOneExecution() throws Exception {
        ToolCallGuard guard = new ToolCallGuard(3);
        AtomicInteger executions = new AtomicInteger();
        CountDownLatch callersReady = new CountDownLatch(8);
        CountDownLatch startCalls = new CountDownLatch(1);
        CountDownLatch executionStarted = new CountDownLatch(1);
        CountDownLatch releaseExecution = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                results.add(executor.submit(() -> {
                    callersReady.countDown();
                    await(startCalls);
                    return guard.execute("search_orders", "AUTO|O1", () -> {
                        executions.incrementAndGet();
                        executionStarted.countDown();
                        await(releaseExecution);
                        return "shared-result";
                    });
                }));
            }

            assertThat(callersReady.await(2, TimeUnit.SECONDS)).isTrue();
            startCalls.countDown();
            assertThat(executionStarted.await(2, TimeUnit.SECONDS)).isTrue();
            releaseExecution.countDown();

            for (Future<String> result : results) {
                assertThat(result.get(2, TimeUnit.SECONDS)).isEqualTo("shared-result");
            }
            assertThat(executions).hasValue(1);
        } finally {
            startCalls.countDown();
            releaseExecution.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void completesFailedCohortBeforeOpeningSameKeyRetry() throws Exception {
        ToolCallGuard guard = new ToolCallGuard(1);
        BlockingRemovalMap calls = new BlockingRemovalMap();
        replaceCalls(guard, calls);
        RuntimeException firstFailure = new IllegalStateException("first failure");
        AtomicInteger executions = new AtomicInteger();
        CountDownLatch firstExecutionStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstFailure = new CountDownLatch(1);
        CountDownLatch retryExecutionStarted = new CountDownLatch(1);
        CountDownLatch releaseRetry = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(4);

        try {
            Future<String> owner = executor.submit(() -> guard.execute(
                    "search_orders", "AUTO|O1", () -> {
                        executions.incrementAndGet();
                        firstExecutionStarted.countDown();
                        await(releaseFirstFailure);
                        throw firstFailure;
                    }));
            assertThat(firstExecutionStarted.await(2, TimeUnit.SECONDS)).isTrue();

            List<Future<String>> waiters = new ArrayList<>();
            for (int index = 0; index < 3; index++) {
                waiters.add(executor.submit(() -> guard.execute(
                        "search_orders", "AUTO|O1", () -> "must-not-run")));
            }
            assertThat(calls.initialWaitersAttached.await(2, TimeUnit.SECONDS)).isTrue();

            releaseFirstFailure.countDown();
            assertThat(calls.removalApplied.await(2, TimeUnit.SECONDS)).isTrue();

            // remove 仍被测试 Map 暂停时，旧调用组的 Future 必须已经先完成为失败。
            assertThat(waiters).allMatch(Future::isDone);

            calls.allowRemovalReturn.countDown();
            assertFutureFailedWith(owner, firstFailure);
            for (Future<String> waiter : waiters) {
                assertFutureFailedWith(waiter, firstFailure);
            }

            Future<String> retryOwner = executor.submit(() -> guard.execute(
                    "search_orders", "AUTO|O1", () -> {
                        executions.incrementAndGet();
                        retryExecutionStarted.countDown();
                        await(releaseRetry);
                        return "recovered";
                    }));
            assertThat(retryExecutionStarted.await(2, TimeUnit.SECONDS)).isTrue();
            Future<String> retryWaiter = executor.submit(() -> guard.execute(
                    "search_orders", "AUTO|O1", () -> "must-not-run"));
            assertThat(calls.retryWaiterAttached.await(2, TimeUnit.SECONDS)).isTrue();
            releaseRetry.countDown();

            assertThat(retryOwner.get(2, TimeUnit.SECONDS)).isEqualTo("recovered");
            assertThat(retryWaiter.get(2, TimeUnit.SECONDS)).isEqualTo("recovered");
            assertThat(executions).hasValue(2);
        } finally {
            releaseFirstFailure.countDown();
            calls.allowRemovalReturn.countDown();
            releaseRetry.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentRejectedFourthKeyDoesNotLeakQuota() throws Exception {
        ToolCallGuard guard = new ToolCallGuard(3);
        CountDownLatch firstExecutionStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstFailure = new CountDownLatch(1);
        AtomicInteger rejectedExecutions = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(7);

        try {
            Future<String> failingCall = executor.submit(() -> guard.execute(
                    "search_orders", "AUTO|O1", () -> {
                        firstExecutionStarted.countDown();
                        await(releaseFirstFailure);
                        throw new IllegalStateException("release quota");
                    }));
            assertThat(firstExecutionStarted.await(2, TimeUnit.SECONDS)).isTrue();
            guard.execute("search_orders", "AUTO|O2", () -> "result-2");
            guard.execute("get_order_logistics", "ORDER_CODE|O1", () -> "result-3");

            CountDownLatch rejectedCallersReady = new CountDownLatch(6);
            CountDownLatch startRejectedCalls = new CountDownLatch(1);
            List<Future<Throwable>> rejections = new ArrayList<>();
            for (int index = 0; index < 6; index++) {
                rejections.add(executor.submit(() -> {
                    rejectedCallersReady.countDown();
                    await(startRejectedCalls);
                    try {
                        guard.execute("search_products", "fish|1|10", () -> {
                            rejectedExecutions.incrementAndGet();
                            return "must-not-run";
                        });
                        return null;
                    } catch (Throwable exception) {
                        return exception;
                    }
                }));
            }
            assertThat(rejectedCallersReady.await(2, TimeUnit.SECONDS)).isTrue();
            startRejectedCalls.countDown();
            for (Future<Throwable> rejection : rejections) {
                assertThat(rejection.get(2, TimeUnit.SECONDS))
                        .isInstanceOf(ToolCallLimitExceededException.class);
            }
            assertThat(rejectedExecutions).hasValue(0);

            releaseFirstFailure.countDown();
            assertThatThrownBy(() -> failingCall.get(2, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class);

            // 额度释放后，之前被拒绝的同一个键应能重新登记并执行，
            // 同时证明拒绝路径既没有遗留 Map 项，也没有泄漏计数。
            assertThat(guard.execute("search_products", "fish|1|10", () -> "accepted"))
                    .isEqualTo("accepted");
        } finally {
            releaseFirstFailure.countDown();
            executor.shutdownNow();
        }
    }

    private void replaceCalls(
            ToolCallGuard guard,
            ConcurrentHashMap<String, CompletableFuture<String>> calls
    ) throws ReflectiveOperationException {
        Field field = ToolCallGuard.class.getDeclaredField("calls");
        field.setAccessible(true);
        field.set(guard, calls);
    }

    private void assertFutureFailedWith(
            Future<String> future,
            Throwable expectedFailure
    ) {
        assertThatThrownBy(() -> future.get(2, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .satisfies(exception ->
                        assertThat(exception.getCause()).isSameAs(expectedFailure));
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("测试线程被中断", exception);
        }
    }

    /** 在条件删除已经生效、但调用方尚未继续执行的位置提供确定性并发控制。 */
    private static final class BlockingRemovalMap
            extends ConcurrentHashMap<String, CompletableFuture<String>> {

        private final AtomicInteger duplicateCount = new AtomicInteger();
        private final CountDownLatch initialWaitersAttached = new CountDownLatch(3);
        private final CountDownLatch retryWaiterAttached = new CountDownLatch(1);
        private final CountDownLatch removalApplied = new CountDownLatch(1);
        private final CountDownLatch allowRemovalReturn = new CountDownLatch(1);

        @Override
        public CompletableFuture<String> putIfAbsent(
                String key,
                CompletableFuture<String> value
        ) {
            CompletableFuture<String> existing = super.putIfAbsent(key, value);
            if (existing != null) {
                int duplicateIndex = duplicateCount.incrementAndGet();
                if (duplicateIndex <= 3) {
                    initialWaitersAttached.countDown();
                } else if (duplicateIndex == 4) {
                    retryWaiterAttached.countDown();
                }
            }
            return existing;
        }

        @Override
        public boolean remove(Object key, Object value) {
            boolean removed = super.remove(key, value);
            if (removed) {
                removalApplied.countDown();
                awaitLatch(allowRemovalReturn);
            }
            return removed;
        }

        private static void awaitLatch(CountDownLatch latch) {
            try {
                latch.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("测试 Map 被中断", exception);
            }
        }
    }
}
