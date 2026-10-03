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

    @Test
    void knowledgeRuleTextDoesNotAuthorizeAnExecutionClaim() {
        assertThat(validator.validate(
                "系统已生成预警。",
                "业务事实：评估状态=EXCEEDED\n"
                        + "企业知识依据：规则要求系统已生成预警"))
                .containsExactly("UNVERIFIED_EXECUTION");
    }

    @Test
    void rejectsUnaskedTraceValidityAndSpecialConditionDigressions() {
        assertThat(validator.validate(
                "查询当前物流状态并根据停滞规则判断如何处理",
                "轨迹内容含非标准文本，需确认是否为有效官方更新；高价值或冷链属性无法判断。",
                "业务事实：评估状态=EXCEEDED，适用环节=干线\n"
                        + "企业知识依据：干线停滞超过24小时应联系承运商"))
                .containsExactly(
                        "TRACE_VALIDITY_INFERENCE",
                        "UNSUPPORTED_SPECIAL_CONDITION");
    }

    @Test
    void allowsARequestedSpecialConditionToBeReportedAsUnknown() {
        assertThat(validator.validate(
                "这个订单是否属于冷链，应该使用什么阈值？",
                "当前业务事实未提供冷链属性，暂时无法确认。",
                "业务事实：评估状态=UNKNOWN\n企业知识依据：冷链中断2小时需升级"))
                .isEmpty();
    }
}
