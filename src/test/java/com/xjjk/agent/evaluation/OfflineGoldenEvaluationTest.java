package com.xjjk.agent.evaluation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import com.xjjk.agent.tool.ToolCallGuard;

class OfflineGoldenEvaluationTest {

    private static final GoldenEvaluationCaseLoader.LoadedDataset DATASET =
            new GoldenEvaluationCaseLoader(new com.fasterxml.jackson.databind.ObjectMapper())
                    .loadDefault();

    static Stream<Arguments> cases() {
        return DATASET.cases().stream().map(Arguments::of);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void executesEveryGoldenCase(GoldenEvaluationCase evaluationCase) {
        GoldenCaseExecution.Execution execution = assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(10),
                () -> GoldenCaseExecution.run(evaluationCase));

        EvaluationAssertions.assertCase(evaluationCase, execution);
    }

    @Test
    void datasetContainsAllApprovedCases() {
        assertThat(DATASET.cases()).hasSize(12);
    }

    @Test
    void checkpointResumeDoesNotRepeatCompletedBusinessBranch() {
        GoldenCaseExecution.CheckpointExecution execution =
                assertTimeoutPreemptively(
                        java.time.Duration.ofSeconds(10),
                        GoldenCaseExecution::runCheckpointTwice);

        assertThat(execution.first().success()).isTrue();
        assertThat(execution.resumed().success()).isTrue();
        verify(execution.orderGateway(), times(1))
                .search(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyString());
        verify(execution.productGateway(), times(1))
                .search(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void toolReuseExecutesSameCanonicalArgumentsOnlyOnce() {
        GoldenEvaluationCase evaluationCase = DATASET.cases().stream()
                .filter(item -> "tool-reuse-001".equals(item.caseId()))
                .findFirst()
                .orElseThrow();
        ToolCallGuard guard = new ToolCallGuard(1);
        AtomicInteger downstreamCalls = new AtomicInteger();

        String first = guard.execute("search_orders", "ORDER_CODE|ORDER_FIXTURE_001", () -> {
            downstreamCalls.incrementAndGet();
            return "fixture-result";
        });
        String reused = guard.execute("search_orders", "ORDER_CODE|ORDER_FIXTURE_001", () -> {
            downstreamCalls.incrementAndGet();
            return "unexpected-second-call";
        });

        assertThat(evaluationCase.expect().status()).isEqualTo("SUCCESS");
        assertThat(reused).isEqualTo(first);
        assertThat(downstreamCalls).hasValue(1);
    }
}
