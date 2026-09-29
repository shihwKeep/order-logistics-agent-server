package com.xjjk.agent.chat.orchestration;

import java.util.Objects;

/** 一个复合问题中的受控信息源需求。只允许保存公开查询标识，不保存内部主键。 */
public record CompositeQueryIntent(
        Source source,
        String value,
        String resultKind) {

    public enum Source {
        BUSINESS,
        KNOWLEDGE,
        GENERAL
    }

    public CompositeQueryIntent {
        source = Objects.requireNonNull(source, "信息源不能为空");
        value = requireText(value, "查询值不能为空");
        resultKind = requireText(resultKind, "结果类型不能为空");
    }

    public static CompositeQueryIntent order(String orderCode) {
        return new CompositeQueryIntent(Source.BUSINESS, orderCode, "order-list");
    }

    public static CompositeQueryIntent logistics(String orderCode) {
        return new CompositeQueryIntent(
                Source.BUSINESS, orderCode, "logistics-timeline");
    }

    public static CompositeQueryIntent product(String productIdentifier) {
        return new CompositeQueryIntent(Source.BUSINESS, productIdentifier, "product-list");
    }

    public static CompositeQueryIntent customerOrders(String customerCode) {
        return new CompositeQueryIntent(Source.BUSINESS, customerCode, "order-list");
    }

    public static CompositeQueryIntent afterSale(String afterSaleCode) {
        return new CompositeQueryIntent(
                Source.BUSINESS, afterSaleCode, "after-sale-detail");
    }

    public static CompositeQueryIntent knowledge(String question) {
        return new CompositeQueryIntent(
                Source.KNOWLEDGE, question, "knowledge-citations");
    }

    public static CompositeQueryIntent general(String analysisRequest) {
        return new CompositeQueryIntent(Source.GENERAL, analysisRequest, "general-analysis");
    }

    private static String requireText(String value, String message) {
        Objects.requireNonNull(value, message);
        String normalized = value.strip();
        if (normalized.isEmpty()
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(message);
        }
        return normalized;
    }
}
