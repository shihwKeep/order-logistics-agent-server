package com.xjjk.agent.chat.orchestration;

import java.util.Objects;

/** 一个复合问题中的受控信息源需求。只允许保存公开查询标识，不保存内部主键。 */
public record CompositeQueryIntent(
        Source source,
        String value,
        String resultKind,
        boolean required,
        String dependsOnResultKind,
        DependencyMode dependencyMode,
        IdentifierSource identifierSource) implements java.io.Serializable {

    public enum Source {
        BUSINESS,
        KNOWLEDGE,
        GENERAL
    }

    public enum DependencyMode {
        NONE,
        LATEST_ORDER
    }

    public enum IdentifierSource {
        USER_INPUT,
        RESOLVED_ORDER
    }

    public CompositeQueryIntent(Source source, String value, String resultKind) {
        this(source, value, resultKind, true, null,
                DependencyMode.NONE, IdentifierSource.USER_INPUT);
    }

    public CompositeQueryIntent(
            Source source,
            String value,
            String resultKind,
            boolean required,
            String dependsOnResultKind) {
        this(source, value, resultKind, required, dependsOnResultKind,
                DependencyMode.NONE, IdentifierSource.USER_INPUT);
    }

    public CompositeQueryIntent {
        source = Objects.requireNonNull(source, "信息源不能为空");
        value = value == null ? "" : value.strip();
        if (value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("查询值不能包含控制字符");
        }
        resultKind = requireText(resultKind, "结果类型不能为空");
        dependsOnResultKind = dependsOnResultKind == null || dependsOnResultKind.isBlank()
                ? null : requireText(dependsOnResultKind, "依赖结果类型不能为空");
        dependencyMode = dependencyMode == null ? DependencyMode.NONE : dependencyMode;
        identifierSource = identifierSource == null
                ? IdentifierSource.USER_INPUT : identifierSource;
        if (dependencyMode == DependencyMode.NONE
                && identifierSource == IdentifierSource.RESOLVED_ORDER) {
            throw new IllegalArgumentException("无依赖意图不能使用已解析订单标识");
        }
        if (dependencyMode != DependencyMode.NONE && dependsOnResultKind == null) {
            throw new IllegalArgumentException("依赖意图必须声明前置结果类型");
        }
    }

    public static CompositeQueryIntent order(String orderCode) {
        return new CompositeQueryIntent(Source.BUSINESS, orderCode, "order-list");
    }

    public static CompositeQueryIntent logistics(String orderCode) {
        return new CompositeQueryIntent(
                Source.BUSINESS, orderCode, "logistics-timeline");
    }

    public static CompositeQueryIntent latestOrderLogistics() {
        return new CompositeQueryIntent(
                Source.BUSINESS, "", "logistics-timeline", true,
                "order-list", DependencyMode.LATEST_ORDER,
                IdentifierSource.RESOLVED_ORDER);
    }

    public static CompositeQueryIntent product(String productIdentifier) {
        return new CompositeQueryIntent(Source.BUSINESS, productIdentifier, "product-list");
    }

    public static CompositeQueryIntent customerOrders(String customerCode) {
        return new CompositeQueryIntent(Source.BUSINESS, customerCode, "order-list");
    }

    public static CompositeQueryIntent customer(String customerCode) {
        return new CompositeQueryIntent(Source.BUSINESS, customerCode, "customer-list");
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

    public static CompositeQueryIntent externalUnavailable(String request) {
        return new CompositeQueryIntent(
                Source.GENERAL, request, "external-data-unavailable", false, null);
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
