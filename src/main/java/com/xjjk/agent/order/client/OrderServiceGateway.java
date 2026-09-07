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
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
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
                    throw new OrderServiceUnavailableException("订单服务调用失败", exception);
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
            // 负状态码表示连接、读超时等 I/O 故障；带 Retry-After 的 5xx 也可能被 Feign 包装成该类型。
            return retryableException.status() < 0
                    || isRetryableStatus(retryableException.status());
        }
        if (exception instanceof FeignException feignException) {
            return isRetryableStatus(feignException.status());
        }
        return false;
    }

    private boolean isRetryableStatus(int status) {
        return status == 502 || status == 503 || status == 504;
    }

    private String failureCategory(RuntimeException exception) {
        if (exception instanceof FeignException feignException && feignException.status() > 0) {
            return "HTTP_" + feignException.status();
        }
        if (exception instanceof RetryableException) {
            return "NETWORK_IO";
        }
        return "CLIENT_ERROR";
    }

    private OrderSearchResult mapSearchResponse(
            OrderServiceResponse<OrderSearchClient.OrderSearchData> response) {
        OrderSearchClient.OrderSearchData data = requireSuccessfulData(response);
        OrderIdentifierType matchedBy = parseMatchedBy(data.matchedBy());
        if (data.total() == null || data.total() < 0
                || data.truncated() == null || data.queriedAt() == null) {
            throw invalidResponse();
        }
        List<OrderSearchClient.OrderCardData> sourceItems = safeList(data.items());
        if (sourceItems.size() > MAX_ORDER_ITEMS) {
            throw invalidResponse();
        }
        List<OrderCard> items = sourceItems.stream().map(this::mapOrderCard).toList();
        return new OrderSearchResult(
                matchedBy, data.total(), data.truncated(), data.queriedAt(), items);
    }

    private OrderCard mapOrderCard(OrderSearchClient.OrderCardData source) {
        if (source == null || isBlank(source.orderCode()) || source.statusCode() == null
                || source.payAmountInFen() == null || source.payAmountInFen() < 0
                || source.goodsTotalCount() == null || source.goodsTotalCount() < 0) {
            throw invalidResponse();
        }
        List<OrderSearchClient.OrderGoodsData> sourceGoods = safeList(source.goods());
        if (sourceGoods.size() > MAX_GOODS_PER_ORDER) {
            throw invalidResponse();
        }
        List<OrderGoodsSummary> goods = sourceGoods.stream().map(this::mapGoods).toList();
        List<String> logisticsCodes = safeList(source.logisticsCodes());
        if (logisticsCodes.stream().anyMatch(this::isBlank)) {
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
        if (sourceOrder == null || isBlank(sourceOrder.orderCode())
                || sourceOrder.statusCode() == null || data.queriedAt() == null
                || data.partial() == null) {
            throw invalidResponse();
        }
        List<ShipmentTimeline> shipments = safeList(data.shipments()).stream()
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
        if (source == null || isBlank(source.logisticsCode()) || isBlank(source.resultStatus())) {
            throw invalidResponse();
        }
        List<OrderLogisticsClient.TrackNodeData> sourceTraces = safeList(source.traces());
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

    private <T> List<T> safeList(List<T> source) {
        // 先保留 DTO 容器用于逐项校验；领域 record 构造时再做不可变复制。
        return source == null ? List.of() : source;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
