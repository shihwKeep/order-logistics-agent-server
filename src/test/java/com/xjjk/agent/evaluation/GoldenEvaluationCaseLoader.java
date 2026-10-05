package com.xjjk.agent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

final class GoldenEvaluationCaseLoader {

    private static final String DEFAULT_RESOURCE =
            "/evaluation/agent-golden-cases.json";
    private static final Pattern SAFE_CASE_ID =
            Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");

    private final ObjectMapper objectMapper;

    GoldenEvaluationCaseLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    LoadedDataset loadDefault() {
        InputStream stream = GoldenEvaluationCaseLoader.class
                .getResourceAsStream(DEFAULT_RESOURCE);
        if (stream == null) {
            throw new IllegalArgumentException("评测资源不存在: " + DEFAULT_RESOURCE);
        }
        return load(stream);
    }

    LoadedDataset load(InputStream stream) {
        try (InputStream input = stream) {
            DatasetDocument document = objectMapper.readValue(input, DatasetDocument.class);
            validate(document);
            return new LoadedDataset(document.version(),
                    List.copyOf(document.cases()));
        } catch (IOException exception) {
            throw new IllegalArgumentException("评测数据读取失败", exception);
        }
    }

    private void validate(DatasetDocument document) {
        if (document == null || blank(document.version())) {
            throw new IllegalArgumentException("评测数据版本不能为空");
        }
        if (document.cases() == null || document.cases().isEmpty()) {
            throw new IllegalArgumentException("评测用例不能为空");
        }
        Set<String> ids = new HashSet<>();
        for (GoldenEvaluationCase evaluationCase : document.cases()) {
            validateCase(document.version(), evaluationCase, ids);
        }
    }

    private void validateCase(
            String datasetVersion,
            GoldenEvaluationCase evaluationCase,
            Set<String> ids) {
        if (evaluationCase == null || blank(evaluationCase.caseId())
                || evaluationCase.caseId().length() > 80
                || !SAFE_CASE_ID.matcher(evaluationCase.caseId()).matches()) {
            throw new IllegalArgumentException("caseId 不安全或为空");
        }
        if (!ids.add(evaluationCase.caseId())) {
            throw new IllegalArgumentException("重复 caseId: " + evaluationCase.caseId());
        }
        if (!datasetVersion.equals(evaluationCase.version())) {
            throw new IllegalArgumentException("case 版本与数据集版本不一致: "
                    + evaluationCase.caseId());
        }
        if (blank(evaluationCase.category()) || blank(evaluationCase.input())
                || empty(evaluationCase.plan()) || empty(evaluationCase.fixtures())) {
            throw new IllegalArgumentException("评测用例缺少必填字段: " + evaluationCase.caseId());
        }
        GoldenEvaluationCase.Expectation expect = evaluationCase.expect();
        if (expect == null) {
            throw new IllegalArgumentException("评测用例缺少 expectation 字段: "
                    + evaluationCase.caseId());
        }
        if (blank(expect.status())) {
            throw new IllegalArgumentException("评测用例缺少 expectation.status: "
                    + evaluationCase.caseId());
        }
        if (expect.resultKinds() == null) {
            throw new IllegalArgumentException("评测用例缺少 expectation.resultKinds: "
                    + evaluationCase.caseId());
        }
        if (expect.knowledgeCalls() == null || expect.knowledgeCalls() < 0) {
            throw new IllegalArgumentException("评测用例缺少 expectation.knowledgeCalls: "
                    + evaluationCase.caseId());
        }
        if (expect.forbiddenCalls() == null) {
            throw new IllegalArgumentException("评测用例缺少 expectation.forbiddenCalls: "
                    + evaluationCase.caseId());
        }
        if (expect.sensitiveValuesAbsent() == null) {
            throw new IllegalArgumentException("评测用例缺少 expectation.sensitiveValuesAbsent: "
                    + evaluationCase.caseId());
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean empty(List<?> values) {
        return values == null || values.isEmpty() || values.stream().anyMatch(value -> value == null);
    }

    record LoadedDataset(String version, List<GoldenEvaluationCase> cases) {
    }

    private record DatasetDocument(String version, List<GoldenEvaluationCase> cases) {
    }
}
