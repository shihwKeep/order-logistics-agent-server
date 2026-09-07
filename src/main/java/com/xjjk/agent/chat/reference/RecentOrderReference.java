package com.xjjk.agent.chat.reference;

/** 当前会话中最近一次唯一明确指向的订单号。 */
public record RecentOrderReference(String orderCode) {

    public RecentOrderReference {
        orderCode = orderCode == null ? null : orderCode.strip();
        // 只允许订单编号常见字符，防止数据库异常值破坏业务上下文边界。
        if (orderCode == null || orderCode.isBlank()
                || !orderCode.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException("订单引用不合法");
        }
    }
}
