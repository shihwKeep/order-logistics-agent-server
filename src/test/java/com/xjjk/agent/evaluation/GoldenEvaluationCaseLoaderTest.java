package com.xjjk.agent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoldenEvaluationCaseLoaderTest {

    private final GoldenEvaluationCaseLoader loader =
            new GoldenEvaluationCaseLoader(new ObjectMapper());

    @Test
    void loadsVersionedCasesAndRejectsDuplicateIds() {
        GoldenEvaluationCaseLoader.LoadedDataset dataset = loader.loadDefault();

        assertThat(dataset.version()).isEqualTo("v1");
        assertThat(dataset.cases()).hasSize(12);
        assertThat(dataset.cases()).extracting(GoldenEvaluationCase::caseId)
                .doesNotHaveDuplicates()
                .containsExactlyInAnyOrder(
                        "order-logistics-001",
                        "order-logistics-policy-001",
                        "customer-order-aftersale-001",
                        "product-price-001",
                        "order-not-found-001",
                        "customer-not-found-001",
                        "sku-not-found-001",
                        "empty-business-skip-kb-001",
                        "downstream-unavailable-001",
                        "tool-reuse-001",
                        "parallel-branches-001",
                        "checkpoint-resume-001");
    }

    @Test
    void rejectsDuplicateIdsAndMissingRequiredExpectationFields() {
        String duplicate = """
                {"version":"v1","cases":[
                  {"caseId":"case-1","version":"v1","category":"A","input":"x","plan":["A"],"fixtures":["f"],"expect":{"status":"SUCCESS","resultKinds":[],"knowledgeCalls":0,"forbiddenCalls":[],"sensitiveValuesAbsent":true}},
                  {"caseId":"case-1","version":"v1","category":"A","input":"x","plan":["A"],"fixtures":["f"],"expect":{"status":"SUCCESS","resultKinds":[],"knowledgeCalls":0,"forbiddenCalls":[],"sensitiveValuesAbsent":true}}
                ]}
                """;
        assertThatThrownBy(() -> loader.load(stream(duplicate)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重复");

        String missing = """
                {"version":"v1","cases":[
                  {"caseId":"case-1","version":"v1","category":"A","input":"x","plan":["A"],"fixtures":["f"],"expect":{"status":"SUCCESS"}}
                ]}
                """;
        assertThatThrownBy(() -> loader.load(stream(missing)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("resultKinds");
    }

    @Test
    void rejectsHighCardinalityOrSensitiveCaseIdentifiers() {
        String sensitive = """
                {"version":"v1","cases":[
                  {"caseId":"XJTS0120260820000011","version":"v1","category":"A","input":"x","plan":["A"],"fixtures":["f"],"expect":{"status":"SUCCESS","resultKinds":[],"knowledgeCalls":0,"forbiddenCalls":[],"sensitiveValuesAbsent":true}}
                ]}
                """;
        assertThatThrownBy(() -> loader.load(stream(sensitive)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("caseId");
    }

    private static ByteArrayInputStream stream(String json) {
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }
}
