package com.xjjk.agent.memory.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ExplicitMemoryCandidateGateTest {

    private final ExplicitMemoryCandidateGate gate = new ExplicitMemoryCandidateGate(500);

    @ParameterizedTest
    @ValueSource(strings = {
            "你以后都叫我石海文",
            "从今往后称呼我为小石",
            "后面跟我交流时叫我老师就行",
            "我希望你以后回答得简洁一些",
            "之后默认使用中文回复我",
            "麻烦记下我只看中文文档",
            "别忘了我平时主要做 Java 开发"
    })
    void admitsBroadMemoryCandidates(String message) {
        assertThat(gate.mightContainExplicitMemory(message)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "帮我查询订单 JTS0102",
            "签收后多久可以退款",
            "以后订单退款要怎么处理",
            "请保存这份订单",
            ""
    })
    void rejectsOrdinaryBusinessQueries(String message) {
        assertThat(gate.mightContainExplicitMemory(message)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", "   \n  "})
    void rejectsBlankMessages(String message) {
        assertThat(gate.mightContainExplicitMemory(message)).isFalse();
    }
}
