package com.xjjk.agent.product.service;

/**
 * 商品主数据服务不可用或返回不可信响应。
 */
public class ProductSearchUnavailableException extends RuntimeException {
    public ProductSearchUnavailableException(String message) {
        super(message);
    }

    public ProductSearchUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
