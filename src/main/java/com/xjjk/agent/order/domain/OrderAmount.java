package com.xjjk.agent.order.domain;

/** 订单卡片金额口径，所有金额均为整数分。 */
public record OrderAmount(
        long goodsTotalInFen,
        long discountInFen,
        long balanceDeductionInFen,
        long freightInFen,
        long receivableInFen) {
}
