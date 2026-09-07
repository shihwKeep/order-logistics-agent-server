package com.xjjk.agent.tool;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
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

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("测试线程被中断", exception);
        }
    }
}
