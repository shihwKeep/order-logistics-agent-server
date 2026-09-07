package com.xjjk.agent.order.service;

/** 订单下游不可用或响应不满足内部契约时的安全异常。 */
public class OrderServiceUnavailableException extends RuntimeException {

    public OrderServiceUnavailableException(String message) {
        super(message);
    }

}
