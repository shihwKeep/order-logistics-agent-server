package com.xjjk.agent.chat.action;

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
        };
    }

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
