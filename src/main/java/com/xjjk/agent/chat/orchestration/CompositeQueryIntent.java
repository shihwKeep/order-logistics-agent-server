package com.xjjk.agent.chat.orchestration;

import java.util.Objects;

/**
 * 一个复合问题中的受控信息源需求，是 Plan 中最小的可执行任务描述。
 *
 * <p>Intent 只保存用户可提供或由前置业务结果解析出的公开查询标识，例如订单号、客户号
 * 和 SKU；不保存数据库内部主键、认证身份或完整业务对象。Workflow 根据 resultKind 把
 * Intent 映射到具体 Gateway，并根据依赖字段决定何时执行。</p>
 *
 * @param source 信息来源：实时业务、企业知识或普通分析
 * @param value 查询值；依赖前置结果解析标识时允许初始为空
 * @param resultKind 该任务成功后产生的结构化结果类型
 * @param required 是否属于本轮完整成功必须取得的结果
 * @param dependsOnResultKind 依赖的前置结果类型，无依赖时为空
 * @param dependencyMode 依赖解析方式
 * @param identifierSource 查询标识来自用户输入还是前置解析结果
 */
public record CompositeQueryIntent(
        Source source,
        String value,
        String resultKind,
        boolean required,
        String dependsOnResultKind,
        DependencyMode dependencyMode,
        IdentifierSource identifierSource) implements java.io.Serializable {

    /** 节点需要访问的信息源类别，用于把 Intent 分配给业务、知识或分析分支。 */
    public enum Source {
        BUSINESS,
        KNOWLEDGE,
        GENERAL
    }

    /**
     * 业务依赖模式。LATEST_ORDER 表示必须先从 order-list 中解析最近订单，
     * 再执行当前 Intent。
     */
    public enum DependencyMode {
        NONE,
        LATEST_ORDER
    }

    /** 查询标识来源；RESOLVED_ORDER 不接受模型或用户在后续阶段覆盖。 */
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

    /**
     * 在计划创建时固定 Intent 不变量，避免把非法依赖关系带入图执行阶段。
     */
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

    /** 创建使用用户订单号查询订单详情/列表的基础业务 Intent。 */
    public static CompositeQueryIntent order(String orderCode) {
        return new CompositeQueryIntent(Source.BUSINESS, orderCode, "order-list");
    }

    /** 创建使用用户订单号查询物流轨迹的基础业务 Intent。 */
    public static CompositeQueryIntent logistics(String orderCode) {
        return new CompositeQueryIntent(
                Source.BUSINESS, orderCode, "logistics-timeline");
    }

    /**
     * 创建“客户订单 -> 解析最近订单 -> 查询物流”的依赖 Intent。
     *
     * <pre>
     * customerOrders(customerCode)
     *   -> resolveLatestOrder(order-list)
     *   -> logistics(resolvedOrderCode)
     * </pre>
     *
     * <p>因此 value 初始为空，实际订单号只能来自 Workflow 写入的 resolvedOrderCode。</p>
     */
    public static CompositeQueryIntent latestOrderLogistics() {
        return new CompositeQueryIntent(
                Source.BUSINESS, "", "logistics-timeline", true,
                "order-list", DependencyMode.LATEST_ORDER,
                IdentifierSource.RESOLVED_ORDER);
    }

    /** 创建使用 SKU、SPU、条码或商品关键词查询商品的业务 Intent。 */
    public static CompositeQueryIntent product(String productIdentifier) {
        return new CompositeQueryIntent(Source.BUSINESS, productIdentifier, "product-list");
    }

    /** 创建使用客户编号查询该客户订单列表的业务 Intent。 */
    public static CompositeQueryIntent customerOrders(String customerCode) {
        return new CompositeQueryIntent(Source.BUSINESS, customerCode, "order-list");
    }

    /** 创建客户基础信息查询 Intent。 */
    public static CompositeQueryIntent customer(String customerCode) {
        return new CompositeQueryIntent(Source.BUSINESS, customerCode, "customer-list");
    }

    /** 创建售后工单详情查询 Intent。 */
    public static CompositeQueryIntent afterSale(String afterSaleCode) {
        return new CompositeQueryIntent(
                Source.BUSINESS, afterSaleCode, "after-sale-detail");
    }

    /** 创建企业知识检索 Intent，查询值使用当前用户完整问题以保留业务语义。 */
    public static CompositeQueryIntent knowledge(String question) {
        return new CompositeQueryIntent(
                Source.KNOWLEDGE, question, "knowledge-citations");
    }

    /** 声明在业务事实和知识证据齐备后需要生成综合分析。 */
    public static CompositeQueryIntent general(String analysisRequest) {
        return new CompositeQueryIntent(Source.GENERAL, analysisRequest, "general-analysis");
    }

    /**
     * 声明用户要求外部市场/竞品数据，但当前没有外部数据源。
     * 该结果为非必需项，只用于让最终回答明确能力边界。
     */
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
