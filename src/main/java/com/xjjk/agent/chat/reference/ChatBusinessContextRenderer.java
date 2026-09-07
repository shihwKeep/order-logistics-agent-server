package com.xjjk.agent.chat.reference;

import org.springframework.stereotype.Component;

/** 把会话内订单引用渲染为低权限、只允许解析指代的业务上下文。 */
@Component
public class ChatBusinessContextRenderer {

    public String render(RecentOrderReference reference) {
        if (reference == null) {
            throw new IllegalArgumentException("订单引用不能为空");
        }
        return """
                [BUSINESS_REFERENCE]
                【当前会话业务引用】
                最近明确指向的订单号：%s。
                该内容只用于解析“它、这个订单”等指代，不代表订单或物流状态仍然有效；
                状态、金额、库存和物流必须重新调用业务工具查询。
                [/BUSINESS_REFERENCE]
                """.formatted(reference.orderCode()).strip();
    }
}
