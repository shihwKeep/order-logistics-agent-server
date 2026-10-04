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

    @Test
    void rejectsUnsupportedPaymentProofAndUnverifiedPricingFacts() {
        assertThat(validator.validate(
                "查询订单商品并结合订单价格规则分析",
                "系统核算金额已固化，建议提供订单创建时的完整支付凭证；"
                        + "无法确认是否适用满减、运费券或积分抵扣。",
                "业务事实：商品合计=60.00，优惠抵扣=0.00\n"
                        + "企业知识依据：订单保存时生成价格快照"))
                .containsExactly(
                        "UNSUPPORTED_REQUESTED_MATERIAL",
                        "UNVERIFIED_BUSINESS_FACT",
                        "UNSUPPORTED_PRICING_DETAIL");
    }

    @Test
    void rejectsCompositeAnswerThatOmitsACompletedBusinessBranch() {
        assertThat(validator.validate(
                "查询订单 XJ202609290001 的商品、物流状态和订单金额，并结合规则分别判断",
                "当前物流状态为‘在途’，已超过停滞阈值。",
                "业务事实：\n"
                        + "order-list：订单数量=1，商品名称=老炊五香牛肉粒，订单成交单价=10.00元\n"
                        + "logistics-timeline：订单状态=在途，停滞评估状态=EXCEEDED\n"
                        + "企业知识依据：\n《物流规则》：超过阈值应核查"))
                .containsExactly("INCOMPLETE_COMPOSITE_ANSWER");
    }

    @Test
    void rejectsUnqueriedAfterSaleFactsAndInferredCategoryClaims() {
        assertThat(validator.validate(
                "查询订单 XJ202609290001 的商品和物流，并结合售后规则判断是否能退货",
                "当前订单尚未签收，也未查询到关联售后工单。牛肉粒属普通食品，非定制/鲜活/数字类，"
                        + "如因物流异常导致超期，可凭物流停滞证据申请例外审核。",
                "业务事实：\n"
                        + "order-list：订单状态=在途，商品名称=老炊五香牛肉粒\n"
                        + "logistics-timeline：订单状态=在途\n"
                        + "企业知识依据：\n《售后规则》：退货需满足品类、时限和完好条件"))
                .containsExactly(
                        "UNQUERIED_AFTER_SALE_RESULT",
                        "UNVERIFIED_AFTER_SALE_FACT",
                        "UNSUPPORTED_AFTER_SALE_CATEGORY",
                        "UNSUPPORTED_AFTER_SALE_EXCEPTION");
    }

    @Test
    void rejectsCompositeAnswerThatOmitsRequestedAfterSaleAnalysis() {
        assertThat(validator.validate(
                "查询订单 XJ202609290001 的商品和物流，并结合售后规则说明是否具备退货判断依据",
                "订单商品已查询，当前物流状态为‘在途’，最新轨迹时间已返回。",
                "业务事实：\n"
                        + "order-list：订单数量=1，商品名称=老炊五香牛肉粒\n"
                        + "logistics-timeline：订单状态=在途\n"
                        + "企业知识依据：\n《售后规则》：退货需满足品类、签收时限和商品完好条件"))
                .containsExactly("INCOMPLETE_AFTER_SALE_ANALYSIS");
    }
}
