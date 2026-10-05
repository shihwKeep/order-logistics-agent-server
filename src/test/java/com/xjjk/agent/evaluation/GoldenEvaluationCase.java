package com.xjjk.agent.evaluation;

import java.util.List;

record GoldenEvaluationCase(
        String caseId,
        String version,
        String category,
        String input,
        List<String> plan,
        List<String> fixtures,
        Expectation expect) {

    record Expectation(
            String status,
            List<String> resultKinds,
            Integer knowledgeCalls,
            List<String> forbiddenCalls,
            Boolean sensitiveValuesAbsent) {
    }
}
