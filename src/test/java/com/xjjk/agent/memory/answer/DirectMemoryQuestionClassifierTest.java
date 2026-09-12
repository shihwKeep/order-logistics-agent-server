package com.xjjk.agent.memory.answer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class DirectMemoryQuestionClassifierTest {
    private final DirectMemoryQuestionClassifier classifier =
            new DirectMemoryQuestionClassifier();

    @ParameterizedTest
    @CsvSource({
            "'你应该怎么称呼我？', PREFERRED_NAME",
            "'我平时主要使用什么编程语言？', PROGRAMMING_LANGUAGE",
            "'你记得我主要做什么工作吗？', WORK_SCOPE",
            "'我偏好用什么语言回答？', ANSWER_LANGUAGE",
            "'我喜欢什么回答风格？', ANSWER_STYLE"
    })
    void classifiesStrictSingleIntentQuestions(String query, String expected) {
        assertThat(classifier.classify(query))
                .contains(DirectMemoryQuestionType.valueOf(expected));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "查询我的退款订单状态",
            "我的编程语言是什么，同时查询订单 C24101816040",
            "我平时主要做 Java 开发。",
            "你还记得关于我的什么？",
            "我喜欢什么回答风格，并且你怎么称呼我？",
            "我该使用什么语言开发订单系统？"
    })
    void rejectsBusinessMixedStatementAndAmbiguousInputs(String query) {
        assertThat(classifier.classify(query)).isEmpty();
    }

    @Test
    void rejectsNullBlankAndOverlongInput() {
        assertThat(classifier.classify(null)).isEmpty();
        assertThat(classifier.classify("  ")).isEmpty();
        assertThat(classifier.classify("我".repeat(81))).isEmpty();
    }
}
