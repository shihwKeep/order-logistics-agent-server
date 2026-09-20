package com.xjjk.agent.chat.service.summary;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xjjk.agent.chat.config.ChatSummaryProperties;
import com.xjjk.agent.chat.domain.MessageStatus;
import com.xjjk.agent.chat.domain.summary.ChatSummaryCandidateBatch;
import com.xjjk.agent.chat.domain.summary.ChatSummaryCandidateTurn;
import com.xjjk.agent.chat.domain.summary.ChatSummaryDraft;
import com.xjjk.agent.chat.domain.summary.ChatSummaryContent;
import com.xjjk.agent.chat.domain.summary.ChatSummaryFact;
import com.xjjk.agent.chat.domain.summary.ChatSummaryGenerationException;
import com.xjjk.agent.chat.domain.summary.ChatSummaryItem;
import com.xjjk.agent.chat.domain.summary.ChatSummarySnapshot;
import com.xjjk.agent.chat.domain.summary.ChatSummarySourceType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static com.xjjk.agent.prompt.PromptCatalogTestFixture.catalog;

class ChatSummaryGeneratorTest {

    @Test
    void shouldSanitizeLabelAndValidateGeneratedSummary() {
        FakeModelClient modelClient = new FakeModelClient(response(validJson(), 10, 5));
        ChatSummaryGenerator generator = generator(modelClient);

        ChatSummaryDraft draft = generator.generate(emptySnapshot(), batch());

        assertThat(draft.content().topic()).isEqualTo("商品咨询");
        assertThat(draft.modelName()).isEqualTo("qwen-plus");
        assertThat(draft.inputTokens()).isEqualTo(10);
        assertThat(draft.outputTokens()).isEqualTo(5);
        assertThat(draft.attemptCount()).isEqualTo(1);
        assertThat(modelClient.requests).singleElement().satisfies(request -> {
            assertThat(request.inputJson())
                    .contains("USER_MESSAGE#1", "ASSISTANT_MESSAGE#2")
                    .contains("ASSISTANT_STATUS#4", "TIMEOUT")
                    .contains("[REDACTED_SECRET]")
                    .doesNotContain("secret123", "不完整回答");
            assertThat(request.corrective()).isFalse();
            assertThat(request.systemPrompt())
                    .isEqualTo("CONFIGURED_SUMMARY_SYSTEM");
        });
    }

    @Test
    void shouldRetryOneInvalidFormatAndAggregateUsage() {
        FakeModelClient modelClient = new FakeModelClient(
                response("not-json", 10, 2),
                response(validJson(), 11, 5)
        );

        ChatSummaryDraft draft = generator(modelClient)
                .generate(emptySnapshot(), batch());

        assertThat(draft.attemptCount()).isEqualTo(2);
        assertThat(draft.inputTokens()).isEqualTo(21);
        assertThat(draft.outputTokens()).isEqualTo(7);
        assertThat(modelClient.requests).extracting(
                ChatSummaryModelClient.Request::corrective
        ).containsExactly(false, true);
    }

    @Test
    void shouldFailWithSafeTypedErrorAfterSecondInvalidResponse() {
        FakeModelClient modelClient = new FakeModelClient(
                response("not-json", 10, 2),
                response("still-not-json", 11, 3)
        );

        assertThatExceptionOfType(ChatSummaryGenerationException.class)
                .isThrownBy(() -> generator(modelClient)
                        .generate(emptySnapshot(), batch()))
                .satisfies(error -> {
                    assertThat(error.code()).isEqualTo(
                            ChatSummaryGenerationException.Code.valueOf(
                                    "INVALID_JSON_RESPONSE"
                            )
                    );
                    assertThat(error.getMessage())
                            .doesNotContain("not-json", "secret123");
                });
    }

    @Test
    void shouldClassifyValidJsonWithUntrustedSourceAsInvalidStructure() {
        String invalidSourceJson = validJson()
                .replace("\"sourceSequence\": 1",
                        "\"sourceSequence\": 999");
        FakeModelClient modelClient = new FakeModelClient(
                response(invalidSourceJson, 10, 2),
                response(invalidSourceJson, 11, 3)
        );

        assertThatExceptionOfType(ChatSummaryGenerationException.class)
                .isThrownBy(() -> generator(modelClient)
                        .generate(emptySnapshot(), batch()))
                .satisfies(error -> {
                    assertThat(error.code().name())
                            .isEqualTo("INVALID_SUMMARY_STRUCTURE");
                    assertThat(error.getMessage())
                            .doesNotContain("999", "secret123");
                });
    }

    @Test
    void shouldAllowValidatedSourcesCarriedByPreviousSummary() {
        String responseJson = """
                {
                  "schemaVersion": 1,
                  "topic": "商品咨询",
                  "currentState": "继续确认",
                  "conversationFacts": [{
                    "content": "用户此前询问商品A1库存",
                    "sourceType": "USER_MESSAGE",
                    "sourceSequence": 1
                  }],
                  "decisions": [],
                  "openQuestions": [{
                    "content": "旧问题仍待确认",
                    "sourceSequence": 2
                  }],
                  "importantEntities": []
                }
                """;
        FakeModelClient modelClient = new FakeModelClient(
                response(responseJson, 10, 5),
                response(responseJson, 10, 5)
        );
        ChatSummaryContent previousContent = new ChatSummaryContent(
                1, "商品咨询", "等待查询",
                List.of(new ChatSummaryFact(
                        "用户询问商品A1库存",
                        ChatSummarySourceType.USER_MESSAGE,
                        1
                )),
                List.of(),
                List.of(new ChatSummaryItem("旧问题仍待确认", 2)),
                List.of()
        );
        ChatSummarySnapshot previous = new ChatSummarySnapshot(
                1, 10567, "conversation-1",
                1, 2, 1, 4,
                previousContent, "summary-v1", "qwen-plus"
        );
        ChatSummaryCandidateBatch nextBatch = new ChatSummaryCandidateBatch(
                1, 10567, "conversation-1",
                2, 2, 4,
                List.of(new ChatSummaryCandidateTurn(
                        "r2", 3, 4,
                        "继续查询", "仍需等待",
                        MessageStatus.SUCCESS, 20, 10
                )),
                3, 4, 20, 10,
                false, false, false, false
        );

        ChatSummaryDraft draft = generator(modelClient)
                .generate(previous, nextBatch);

        assertThat(draft.content().conversationFacts())
                .extracting(ChatSummaryFact::sourceSequence)
                .containsExactly(1L);
        assertThat(draft.content().openQuestions())
                .extracting(ChatSummaryItem::sourceSequence)
                .containsExactly(2L);
    }

    private static ChatSummaryGenerator generator(
            ChatSummaryModelClient modelClient
    ) {
        return new ChatSummaryGenerator(
                new ObjectMapper(),
                modelClient,
                new SensitiveContentSanitizer(),
                new ChatSummaryValidator(),
                propertiesForSummaryTests(),
                catalog()
        );
    }

    private static ChatSummaryCandidateBatch batch() {
        List<ChatSummaryCandidateTurn> turns = List.of(
                new ChatSummaryCandidateTurn(
                        "r1", 1, 2,
                        "password=secret123 商品A1库存是多少",
                        "等待商品系统查询",
                        MessageStatus.SUCCESS,
                        50, 20
                ),
                new ChatSummaryCandidateTurn(
                        "r2", 3, 4,
                        "继续查询",
                        null,
                        MessageStatus.TIMEOUT,
                        30, 10
                )
        );
        return new ChatSummaryCandidateBatch(
                1, 10567, "conversation-1",
                0, 2, 4,
                turns, 1, 4,
                80, 30,
                false, false, false, false
        );
    }

    private static ChatSummarySnapshot emptySnapshot() {
        return new ChatSummarySnapshot(
                1, 10567, "conversation-1",
                0, 0, 0, 4,
                null, null, null
        );
    }

    private static String validJson() {
        return """
                {
                  "schemaVersion": 1,
                  "topic": "商品咨询",
                  "currentState": "等待商品系统查询",
                  "conversationFacts": [{
                    "content": "用户询问商品A1库存",
                    "sourceType": "USER_MESSAGE",
                    "sourceSequence": 1
                  }],
                  "decisions": [],
                  "openQuestions": [{
                    "content": "商品A1库存是多少",
                    "sourceSequence": 1
                  }],
                  "importantEntities": [{
                    "entityType": "PRODUCT_CODE",
                    "displayValue": "A1",
                    "sourceType": "USER_MESSAGE",
                    "sourceSequence": 1
                  }]
                }
                """;
    }

    private static ChatSummaryModelClient.Response response(
            String json,
            Integer inputTokens,
            Integer outputTokens
    ) {
        return new ChatSummaryModelClient.Response(
                json,
                "qwen-plus",
                inputTokens,
                outputTokens
        );
    }

    static ChatSummaryProperties propertiesForSummaryTests() {
        return new ChatSummaryProperties(
                true, false, false, 1,
                100, 2, 1, 100,
                10, 1_000, 500,
                20, 30, 20,
                "summary-v1", "qwen-plus", 0.1,
                Duration.ofSeconds(10),
                new ChatSummaryProperties.Worker(
                        1, 1, 10, 2,
                        Duration.ofSeconds(5),
                        Duration.ofMinutes(1),
                        Duration.ofSeconds(30), "agent-1"
                ),
                new ChatSummaryProperties.Retry(
                        3, Duration.ofSeconds(1),
                        Duration.ofSeconds(10), Duration.ZERO
                )
        );
    }

    private static final class FakeModelClient
            implements ChatSummaryModelClient {

        private final ArrayDeque<Response> responses;
        private final List<Request> requests = new ArrayList<>();

        private FakeModelClient(Response... responses) {
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        @Override
        public Response generate(Request request) {
            requests.add(request);
            return responses.removeFirst();
        }
    }
}
