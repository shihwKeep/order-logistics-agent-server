package com.xjjk.agent.order.client;

import com.xjjk.agent.identity.domain.AgentIdentity;
import com.xjjk.agent.order.domain.OrderCard;
import com.xjjk.agent.order.domain.OrderGoodsSummary;
import com.xjjk.agent.order.domain.OrderIdentifierType;
import com.xjjk.agent.order.domain.OrderLogisticsResult;
import com.xjjk.agent.order.domain.OrderSearchResult;
import com.xjjk.agent.order.domain.ShipmentTimeline;
import com.xjjk.agent.order.domain.TrackNode;
import com.xjjk.agent.order.service.OrderQueryGateway;
import com.xjjk.agent.order.service.OrderServiceUnavailableException;
import feign.FeignException;
import feign.RetryableException;
import java.net.ConnectException;
import java.net.ProtocolException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import javax.net.ssl.SSLException;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 将 order 内部接口适配成 Agent 的脱敏领域模型。
 *
 * <p>身份头、响应校验和显式重试都收口在该边界，避免工具层自行拼接可信身份或误重试业务错误。
 */
@Slf4j
@Component
public class OrderServiceGateway implements OrderQueryGateway {
    private static final int ORDER_SUCCESS_CODE = 1000;
    private static final int MAX_ATTEMPTS = 2;
    private static final int MAX_ORDER_ITEMS = 5;
    private static final int MAX_GOODS_PER_ORDER = 3;
    private static final int MAX_LOGISTICS_CODES = 10;
    private static final int MAX_SHIPMENTS = 10;
    private static final int MAX_TRACKS_PER_SHIPMENT = 50;

    private final OrderSearchClient searchClient;
    private final OrderLogisticsClient logisticsClient;
    private final String internalToken;

    public OrderServiceGateway(
            OrderSearchClient searchClient,
            OrderLogisticsClient logisticsClient,
            @Value("${integration.order.internal-token}") String internalToken) {
        this.searchClient = Objects.requireNonNull(searchClient, "searchClient 不能为空");
        this.logisticsClient = Objects.requireNonNull(logisticsClient, "logisticsClient 不能为空");
        if (internalToken == null || internalToken.isBlank()) {
            throw new IllegalArgumentException("integration.order.internal-token 不能为空");
        }
        this.internalToken = internalToken;
    }

    @Override
    public OrderSearchResult search(
            String identifier,
            OrderIdentifierType identifierType,
            AgentIdentity identity,
            String requestId) {
        validateRequest(identifier, identifierType, identity, requestId);
        long startedAt = System.nanoTime();

        // tenant/user/org 均来自已认证身份，模型和调用方没有覆盖可信头的入口。
        OrderServiceResponse<OrderSearchClient.OrderSearchData> response = invokeWithRetry(
                "order_search",
                identifierType,
                requestId,
                () -> searchClient.search(
                        internalToken,
                        identity.tenantId(),
                        identity.userId(),
                        identity.orgId(),
                        requestId,
                        new OrderSearchClient.OrderSearchRequest(identifier.trim(), identifierType)));
        OrderSearchResult result = mapSearchResponse(response);
        log.info(
                "order_gateway requestId={}, operation=order_search, identifierType={}, resultCount={}, durationMs={}, status=SUCCESS",
                requestId,
                identifierType,
                result.items().size(),
                elapsedMillis(startedAt));
        return result;
    }

    @Override
    public OrderLogisticsResult logistics(
            String identifier,
            OrderIdentifierType identifierType,
            AgentIdentity identity,
            String requestId) {
        validateRequest(identifier, identifierType, identity, requestId);
        long startedAt = System.nanoTime();

        // 物流查询同样只传业务编号，禁止把 orderId 暴露为可调用参数。
        OrderServiceResponse<OrderLogisticsClient.OrderLogisticsData> response = invokeWithRetry(
                "order_logistics",
                identifierType,
                requestId,
                () -> logisticsClient.logistics(
                        internalToken,
                        identity.tenantId(),
                        identity.userId(),
                        identity.orgId(),
                        requestId,
                        new OrderLogisticsClient.OrderLogisticsRequest(identifier.trim(), identifierType)));
        OrderLogisticsResult result = mapLogisticsResponse(response);
        log.info(
                "order_gateway requestId={}, operation=order_logistics, identifierType={}, resultCount={}, durationMs={}, status=SUCCESS",
                requestId,
                identifierType,
                result.shipments().size(),
                elapsedMillis(startedAt));
        return result;
    }

    private <T> OrderServiceResponse<T> invokeWithRetry(
            String operation,
            OrderIdentifierType identifierType,
            String requestId,
            Supplier<OrderServiceResponse<T>> invocation) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return invocation.get();
            } catch (RuntimeException exception) {
                boolean retryable = isTransientFailure(exception);
                if (!retryable || attempt == MAX_ATTEMPTS) {
                    log.warn(
                            "order_gateway requestId={}, operation={}, identifierType={}, attempt={}, status=FAILED, failureCategory={}",
                            requestId,
                            operation,
                            identifierType,
                            attempt,
                            failureCategory(exception));
                    // Feign 异常可能携带请求头或下游响应正文，跨适配器边界时必须切断原始异常链。
                    throw new OrderServiceUnavailableException("订单服务调用失败");
                }
                log.warn(
                        "order_gateway requestId={}, operation={}, identifierType={}, attempt={}, status=RETRYING, failureCategory={}",
                        requestId,
                        operation,
                        identifierType,
                        attempt,
                        failureCategory(exception));
            }
        }
        throw new IllegalStateException("unreachable");
    }

    private boolean isTransientFailure(RuntimeException exception) {
        if (exception instanceof RetryableException retryableException) {
            // 带 Retry-After 的 5xx 可能被 Feign 包成 RetryableException，仍按明确 HTTP 状态判断。
            if (isRetryableStatus(retryableException.status())) {
                return true;
            }
            // 负状态码只表示请求执行阶段失败，并不天然可重试；必须继续核对明确的底层原因。
            return retryableException.status() < 0
                    && hasRetryableConnectionOrReadTimeoutCause(retryableException.getCause());
        }
        if (exception instanceof FeignException feignException) {
            return isRetryableStatus(feignException.status());
        }
        return false;
    }

    private boolean isRetryableStatus(int status) {
        return status == 502 || status == 503 || status == 504;
    }

    private boolean hasRetryableConnectionOrReadTimeoutCause(Throwable cause) {
        boolean foundRetryableCause = false;
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = cause;
             current != null && visited.add(current);
             current = current.getCause()) {
            // DNS、TLS/证书和协议错误通常不会通过同请求重试恢复；即使链中还包着连接异常也拒绝重试。
            if (current instanceof UnknownHostException
                    || current instanceof SSLException
                    || current instanceof ProtocolException
                    || current instanceof org.apache.hc.core5.http.ProtocolException) {
                return false;
            }
            if (current instanceof ConnectException
                    || current instanceof SocketTimeoutException
                    || current instanceof ConnectionRequestTimeoutException) {
                foundRetryableCause = true;
            }
        }
        return foundRetryableCause;
    }

    private String failureCategory(RuntimeException exception) {
        if (exception instanceof FeignException feignException && feignException.status() > 0) {
            return "HTTP_" + feignException.status();
        }
        if (exception instanceof RetryableException) {
            return hasRetryableConnectionOrReadTimeoutCause(exception.getCause())
                    ? "NETWORK_TRANSIENT"
                    : "NETWORK_NON_TRANSIENT";
        }
        return "CLIENT_ERROR";
    }

    private OrderSearchResult mapSearchResponse(
            OrderServiceResponse<OrderSearchClient.OrderSearchData> response) {
        OrderSearchClient.OrderSearchData data = requireSuccessfulData(response);
        OrderIdentifierType matchedBy = parseMatchedBy(data.matchedBy());
        // 下游契约要求集合字段始终显式返回数组；null 代表响应结构损坏，不能降级成空结果。
        if (data.total() == null || data.total() < 0
                || data.truncated() == null || data.queriedAt() == null
                || data.items() == null) {
            throw invalidResponse();
        }
        List<OrderSearchClient.OrderCardData> sourceItems = data.items();
        // Agent 不静默截断越界数据，避免把下游契约漂移伪装成一次正常查询。
        if (sourceItems.size() > MAX_ORDER_ITEMS
                || data.total() < sourceItems.size()
                || (!data.truncated() && data.total() != sourceItems.size())) {
            throw invalidResponse();
        }
        List<OrderCard> items = sourceItems.stream().map(this::mapOrderCard).toList();
        return new OrderSearchResult(
                matchedBy, data.total(), data.truncated(), data.queriedAt(), items);
    }

    private OrderCard mapOrderCard(OrderSearchClient.OrderCardData source) {
        if (source == null || isBlank(source.orderCode()) || source.statusCode() == null
                || source.payAmountInFen() == null || source.payAmountInFen() < 0
                || source.goodsTotalCount() == null || source.goodsTotalCount() < 0
                || source.goods() == null || source.logisticsCodes() == null) {
            throw invalidResponse();
        }
        List<OrderSearchClient.OrderGoodsData> sourceGoods = source.goods();
        if (sourceGoods.size() > MAX_GOODS_PER_ORDER
                || source.goodsTotalCount() < sourceGoods.size()) {
            throw invalidResponse();
        }
        List<OrderGoodsSummary> goods = sourceGoods.stream().map(this::mapGoods).toList();
        List<String> logisticsCodes = source.logisticsCodes();
        if (logisticsCodes.size() > MAX_LOGISTICS_CODES
                || logisticsCodes.stream().anyMatch(this::isBlank)) {
            throw invalidResponse();
        }
        return new OrderCard(
                source.orderCode(),
                source.outerOrderCode(),
                source.statusCode(),
                source.statusText(),
                source.orderTime(),
                source.customerDisplayName(),
                source.payAmountInFen(),
                source.goodsTotalCount(),
                goods,
                source.carrierName(),
                logisticsCodes);
    }

    private OrderGoodsSummary mapGoods(OrderSearchClient.OrderGoodsData source) {
        if (source == null || isBlank(source.goodsName())
                || source.quantity() == null || source.quantity() <= 0) {
            throw invalidResponse();
        }
        return new OrderGoodsSummary(
                source.goodsName(), source.skuCode(), source.specification(), source.quantity());
    }

    private OrderLogisticsResult mapLogisticsResponse(
            OrderServiceResponse<OrderLogisticsClient.OrderLogisticsData> response) {
        OrderLogisticsClient.OrderLogisticsData data = requireSuccessfulData(response);
        OrderLogisticsClient.OrderSummaryData sourceOrder = data.order();
        // shipments/traces 都是契约必填数组；数量越界直接判为非法响应，不在 Agent 层截断或合并。
        if (sourceOrder == null || isBlank(sourceOrder.orderCode())
                || sourceOrder.statusCode() == null || data.queriedAt() == null
                || data.partial() == null || data.shipments() == null
                || data.shipments().size() > MAX_SHIPMENTS) {
            throw invalidResponse();
        }
        List<ShipmentTimeline> shipments = data.shipments().stream()
                .map(this::mapShipment)
                .toList();
        return new OrderLogisticsResult(
                new OrderLogisticsResult.OrderSummary(
                        sourceOrder.orderCode(), sourceOrder.statusCode(), sourceOrder.statusText()),
                data.queriedAt(),
                data.partial(),
                shipments);
    }

    private ShipmentTimeline mapShipment(OrderLogisticsClient.ShipmentData source) {
        if (source == null || isBlank(source.logisticsCode()) || isBlank(source.resultStatus())
                || source.traces() == null) {
            throw invalidResponse();
        }
        List<OrderLogisticsClient.TrackNodeData> sourceTraces = source.traces();
        if (sourceTraces.size() > MAX_TRACKS_PER_SHIPMENT) {
            throw invalidResponse();
        }
        // 保持物流服务给出的轨迹顺序，不在 Agent 层拼接或重新排序轨迹正文。
        List<TrackNode> traces = sourceTraces.stream().map(this::mapTrackNode).toList();
        return new ShipmentTimeline(
                source.logisticsCode(),
                source.carrierName(),
                source.resultStatus(),
                source.latestStatusText(),
                source.latestTrace(),
                traces);
    }

    private TrackNode mapTrackNode(OrderLogisticsClient.TrackNodeData source) {
        if (source == null || isBlank(source.description())) {
            throw invalidResponse();
        }
        return new TrackNode(source.time(), source.location(), source.description());
    }

    private <T> T requireSuccessfulData(OrderServiceResponse<T> response) {
        // 业务失败、空包和解码后缺少 data 都不可重试，统一向上暴露无敏感细节的安全异常。
        if (response == null || response.code() == null
                || response.code() != ORDER_SUCCESS_CODE || response.data() == null) {
            throw invalidResponse();
        }
        return response.data();
    }

    private OrderIdentifierType parseMatchedBy(String matchedBy) {
        if (isBlank(matchedBy)) {
            throw invalidResponse();
        }
        try {
            return OrderIdentifierType.valueOf(matchedBy);
        } catch (IllegalArgumentException exception) {
            throw invalidResponse();
        }
    }

    private void validateRequest(
            String identifier,
            OrderIdentifierType identifierType,
            AgentIdentity identity,
            String requestId) {
        if (isBlank(identifier) || identifierType == null || identity == null || isBlank(requestId)) {
            throw new IllegalArgumentException("订单查询参数不完整");
        }
    }

    private OrderServiceUnavailableException invalidResponse() {
        return new OrderServiceUnavailableException("订单服务响应不可用");
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
