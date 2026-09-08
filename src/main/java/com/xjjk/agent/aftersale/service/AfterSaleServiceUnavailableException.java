package com.xjjk.agent.aftersale.service;

/** 对上层屏蔽下游异常正文、请求头及实现细节。 */
public class AfterSaleServiceUnavailableException extends RuntimeException {
    public AfterSaleServiceUnavailableException(String message) {
        super(message);
    }
}
