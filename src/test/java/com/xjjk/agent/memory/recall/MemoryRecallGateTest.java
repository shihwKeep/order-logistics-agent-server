package com.xjjk.agent.memory.recall;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryRecallGateTest {
    private final MemoryRecallGate gate = new MemoryRecallGate();

    @ParameterizedTest
    @ValueSource(strings = {
            "我平时主要使用什么编程语言？",
            "你还记得我喜欢怎样的回答风格吗",
            "我以前告诉过你我是做什么工作的吗",
            "请按我的语言偏好回答",
            "应该怎么称呼我"
    })
    void opensForPersonalPreferenceAndHistoryQuestions(String query) {
        assertThat(gate.shouldRetrieve(query)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "查询订单 C24101816040",
            "我申请退款怎么处理",
            "我的物流到哪里了",
            "客户投诉首次响应时限是多少",
            "签收后多久可以退款"
    })
    void staysClosedForOrdinaryBusinessQuestions(String query) {
        assertThat(gate.shouldRetrieve(query)).isFalse();
    }
}
