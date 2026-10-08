package com.xjjk.agent.chat.action;

import com.xjjk.agent.aftersale.domain.AfterSaleDetailResult;
import com.xjjk.agent.aftersale.service.AfterSaleQueryGateway;
import com.xjjk.agent.aftersale.service.AfterSaleServiceUnavailableException;
import com.xjjk.agent.aftersale.tool.AfterSaleToolAvailability;
import com.xjjk.agent.chat.api.dto.ChatActionRequest;
import com.xjjk.agent.common.api.ApiErrorCode;
import com.xjjk.agent.common.exception.BusinessException;
import com.xjjk.agent.identity.domain.AgentIdentity;
import com.xjjk.agent.customer.service.CustomerOrderQueryResult;
import com.xjjk.agent.customer.service.CustomerOrderQueryService;
import com.xjjk.agent.customer.service.CustomerServiceUnavailableException;
import com.xjjk.agent.order.domain.OrderIdentifierType;
import com.xjjk.agent.order.domain.OrderLogisticsResult;
import com.xjjk.agent.order.service.OrderQueryGateway;
import com.xjjk.agent.order.service.OrderServiceUnavailableException;
import com.xjjk.agent.order.tool.OrderToolAvailability;
import com.xjjk.agent.product.domain.ProductSearchQuery;
import com.xjjk.agent.product.domain.ProductSearchResult;
import com.xjjk.agent.product.service.ProductSearchGateway;
import com.xjjk.agent.product.service.ProductSearchUnavailableException;
import com.xjjk.agent.tool.ToolUiResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 执行前端卡片触发的白名单动作。
 *
 * <p>动作绕过模型的工具选择，但不会绕过组织灰度、可信身份或订单服务鉴权。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatActionDispatcher {

    private final OrderQueryGateway orderGateway;
    private final CustomerOrderQueryService customerOrderQueryService;
    private final OrderToolAvailability availability;
    private final AfterSaleQueryGateway afterSaleGateway;
    private final AfterSaleToolAvailability afterSaleAvailability;
    private final ProductSearchGateway productSearchGateway;

    /**
     * 解析并执行前端提交的白名单动作。
     *
     * <p>动作类型和业务编号来自前端卡片，但租户、用户和组织始终来自已认证身份；
     * 未知动作在进入任何下游调用前失败。</p>
     */
    public DispatchResult dispatch(
            ChatActionRequest action,
            AgentIdentity identity,
            String requestId) {
        Objects.requireNonNull(action, "聊天动作不能为空");
        Objects.requireNonNull(identity, "认证身份不能为空");
        ChatActionType type = ChatActionType.parse(action.type());
        return switch (type) {
            case QUERY_ORDER_LOGISTICS -> queryOrderLogistics(
                    action.orderCode(), identity, requestId);
            case QUERY_CUSTOMER_ORDERS -> queryCustomerOrders(
                    action.customerCode(), identity, requestId);
            case QUERY_AFTER_SALE_DETAIL -> queryAfterSaleDetail(
                    action.afterSaleCode(), identity, requestId);
            case QUERY_PRODUCT -> queryProduct(
                    action.productKeyword(), action.productPageIndex(), identity, requestId);
        };
    }

    /** 明确商品标识的直查动作，绕过模型工具选择但复用同一商品网关和 UI 协议。 */
    private DispatchResult queryProduct(
            String productKeyword,
            Integer productPageIndex,
            AgentIdentity identity,
            String requestId) {
        String normalizedKeyword = normalizeProductKeyword(productKeyword);
        int pageIndex = normalizeProductPageIndex(productPageIndex);
        try {
            ProductSearchResult result = productSearchGateway.search(
                    ProductSearchQuery.of(normalizedKeyword, pageIndex, 10));
            ToolUiResult uiResult = new ToolUiResult(
                    "search_products",
                    "product-list",
                    1,
                    java.time.OffsetDateTime.now(),
                    result);
            String assistantText;
            if (result.total() == 0) {
                assistantText = pageIndex > 1
                        ? "未查询到商品标识 " + normalizedKeyword + " 的第"
                        + pageIndex + "页商品信息。"
                        : "未查询到商品标识 " + normalizedKeyword
                        + " 对应的商品信息。";
            } else {
                assistantText = pageIndex > 1
                        ? "已为你查询商品标识 " + normalizedKeyword + " 的第"
                        + pageIndex + "页，商品卡片已展示。"
                        : "已为你查询商品标识 " + normalizedKeyword
                        + " 的详情，商品卡片已展示。";
            }
            return new DispatchResult(uiResult, assistantText);
        } catch (ProductSearchUnavailableException exception) {
            log.warn(
                    "chat_action_failed requestId={}, action={}, exceptionType={}",
                    requestId,
                    ChatActionType.QUERY_PRODUCT,
                    exception.getClass().getSimpleName());
            throw new BusinessException(ApiErrorCode.CHAT_ACTION_UNAVAILABLE);
        }
    }

    /** 查询售后详情，并把完整领域结果转换成前端卡片协议。 */
    private DispatchResult queryAfterSaleDetail(
            String afterSaleCode,
            AgentIdentity identity,
            String requestId) {
        if (!afterSaleAvailability.isDetailAvailable(identity)) {
            throw new BusinessException(ApiErrorCode.CHAT_ACTION_UNAVAILABLE);
        }
        String normalizedAfterSaleCode = normalizeAfterSaleCode(afterSaleCode);
        try {
            // 卡片动作仍从 Gateway 进入可信请求头、组织权限、熔断与响应校验链路。
            AfterSaleDetailResult result = afterSaleGateway.detail(
                    normalizedAfterSaleCode, identity, requestId);
            return new DispatchResult(
                    new ToolUiResult(
                            "get_after_sale_detail",
                            "after-sale-detail",
                            1,
                            result.queriedAt(),
                            result),
                    "已为你查询售后工单 " + normalizedAfterSaleCode
                            + " 的详情，详细信息已展示。");
        } catch (AfterSaleServiceUnavailableException exception) {
            log.warn(
                    "chat_action_failed requestId={}, action={}, exceptionType={}",
                    requestId,
                    ChatActionType.QUERY_AFTER_SALE_DETAIL,
                    exception.getClass().getSimpleName());
            throw new BusinessException(ApiErrorCode.CHAT_ACTION_UNAVAILABLE);
        }
    }

    /** 先以客户编号解析可信客户，再查询该客户有权访问的订单。 */
    private DispatchResult queryCustomerOrders(
            String customerCode,
            AgentIdentity identity,
            String requestId) {
        if (!availability.isCustomerOrderAvailable(identity)) {
            throw new BusinessException(ApiErrorCode.CHAT_ACTION_UNAVAILABLE);
        }
        String normalizedCustomerCode = normalizeCustomerCode(customerCode);
        try {
            // 与模型工具共用同一应用服务，确保客户权限解析、内部 ID 使用和订单权限条件完全一致。
            CustomerOrderQueryResult result = customerOrderQueryService.query(
                    normalizedCustomerCode, identity, requestId);
            return switch (result.resolution()) {
                case NOT_FOUND -> new DispatchResult(
                        null,
                        "未查询到客户编号 " + normalizedCustomerCode + "，请核对后重试。");
                case AMBIGUOUS -> new DispatchResult(
                        null,
                        "客户编号 " + normalizedCustomerCode + " 无法唯一定位客户，请核对后重试。");
                case FOUND -> new DispatchResult(
                        new ToolUiResult(
                                "list_customer_orders",
                                "order-list",
                                1,
                                result.orders().queriedAt(),
                                result.orders()),
                        "已为你查询客户 " + result.customerCode()
                                + " 的订单，订单卡片已展示。");
            };
        } catch (CustomerServiceUnavailableException | OrderServiceUnavailableException exception) {
            log.warn(
                    "chat_action_failed requestId={}, action={}, exceptionType={}",
                    requestId,
                    ChatActionType.QUERY_CUSTOMER_ORDERS,
                    exception.getClass().getSimpleName());
            throw new BusinessException(ApiErrorCode.CHAT_ACTION_UNAVAILABLE);
        }
    }

    /** 使用卡片携带的完整订单号查询实时物流，不允许前端直接传内部 orderId。 */
    private DispatchResult queryOrderLogistics(
            String orderCode,
            AgentIdentity identity,
            String requestId) {
        if (!availability.isLogisticsAvailable(identity)) {
            throw new BusinessException(ApiErrorCode.CHAT_ACTION_UNAVAILABLE);
        }
        String normalizedOrderCode = normalizeOrderCode(orderCode);
        try {
            OrderLogisticsResult result = orderGateway.logistics(
                    normalizedOrderCode,
                    OrderIdentifierType.ORDER_CODE,
                    identity,
                    requestId);
            ToolUiResult uiResult = new ToolUiResult(
                    "get_order_logistics",
                    "logistics-timeline",
                    1,
                    result.queriedAt(),
                    result);
            String assistantText = "已为你查询订单 " + normalizedOrderCode
                    + " 的最新物流，详细轨迹已展示。";
            return new DispatchResult(uiResult, assistantText);
        } catch (OrderServiceUnavailableException exception) {
            log.warn(
                    "chat_action_failed requestId={}, action={}, exceptionType={}",
                    requestId,
                    ChatActionType.QUERY_ORDER_LOGISTICS,
                    exception.getClass().getSimpleName());
            throw new BusinessException(ApiErrorCode.CHAT_ACTION_UNAVAILABLE);
        }
    }

    /** 清理并限制订单号，控制字符或超长值在访问下游前被拒绝。 */
    private String normalizeOrderCode(String orderCode) {
        if (orderCode == null) {
            throw new BusinessException(ApiErrorCode.VALIDATION_ERROR);
        }
        String normalized = orderCode.strip();
        if (normalized.isEmpty()
                || normalized.length() > 64
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new BusinessException(ApiErrorCode.VALIDATION_ERROR);
        }
        return normalized;
    }

    /** 清理并限制客户编号；客户内部主键不属于前端动作协议。 */
    private String normalizeCustomerCode(String customerCode) {
        if (customerCode == null) {
            throw new BusinessException(ApiErrorCode.VALIDATION_ERROR);
        }
        String normalized = customerCode.strip();
        if (normalized.isEmpty()
                || normalized.length() > 128
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new BusinessException(ApiErrorCode.VALIDATION_ERROR);
        }
        return normalized;
    }

    /** 清理并限制售后工单号，保证固定回答和下游参数使用同一规范化值。 */
    private String normalizeAfterSaleCode(String afterSaleCode) {
        if (afterSaleCode == null) {
            throw new BusinessException(ApiErrorCode.VALIDATION_ERROR);
        }
        String normalized = afterSaleCode.strip();
        if (normalized.isEmpty()
                || normalized.length() > 64
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new BusinessException(ApiErrorCode.VALIDATION_ERROR);
        }
        return normalized;
    }

    /** 清理商品标识，阻止控制字符或超长值进入商品服务。 */
    private String normalizeProductKeyword(String productKeyword) {
        if (productKeyword == null) {
            throw new BusinessException(ApiErrorCode.VALIDATION_ERROR);
        }
        String normalized = productKeyword.strip();
        if (normalized.isEmpty()
                || normalized.length() > 64
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new BusinessException(ApiErrorCode.VALIDATION_ERROR);
        }
        return normalized;
    }

    /** 限制确定性分页动作的页码，避免客户端构造超大下游查询。 */
    private int normalizeProductPageIndex(Integer productPageIndex) {
        if (productPageIndex == null) {
            return 1;
        }
        if (productPageIndex < 1 || productPageIndex > 1000) {
            throw new BusinessException(ApiErrorCode.VALIDATION_ERROR);
        }
        return productPageIndex;
    }

    /** 分发成功后的完整 UI 结果与服务端固定回答正文。 */
    public record DispatchResult(
            ToolUiResult uiResult,
            String assistantText) {

        public DispatchResult {
            if (assistantText == null || assistantText.isBlank()) {
                throw new IllegalArgumentException("动作回答正文不能为空");
            }
        }
    }
}
