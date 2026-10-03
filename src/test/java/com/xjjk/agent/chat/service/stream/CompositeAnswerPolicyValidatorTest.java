package com.xjjk.agent.chat.service.stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CompositeAnswerPolicyValidatorTest {

    private final CompositeAnswerPolicyValidator validator = new CompositeAnswerPolicyValidator();

    @Test
    void acceptsAdviceWhenNoExecutionClaimIsPresent() {
        assertThat(validator.validate(
                "已达到停滞阈值，客服应生成预警并联系承运商核查。",
                "评估状态=EXCEEDED，适用阈值小时=24"))
                .isEmpty();
    }

    @Test
    void rejectsExecutionTenseWithoutBusinessExecutionFact() {
        assertThat(validator.validate(
                "系统已生成预警，正在核实中。",
                "评估状态=EXCEEDED，适用阈值小时=24"))
                .containsExactly("UNVERIFIED_EXECUTION");
    }

    @Test
    void rejectsValidityInferenceFromTraceText() {
        assertThat(validator.validate(
                "距最新轨迹已超24小时无有效更新。",
                "最新轨迹时间=2026-08-20 15:58:06，距最新轨迹小时=1063"))
                .containsExactly("TRACE_VALIDITY_INFERENCE");
    }

    @Test
    void allowsAClaimWhenTheBusinessContextExplicitlyConfirmsIt() {
        assertThat(validator.validate(
                "系统已生成预警。",
                "评估状态=EXCEEDED，执行结果=已生成预警"))
                .isEmpty();
    }
}
