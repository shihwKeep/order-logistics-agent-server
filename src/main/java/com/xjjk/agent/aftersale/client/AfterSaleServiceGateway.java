package com.xjjk.agent.aftersale.client;

import com.xjjk.agent.aftersale.config.AfterSaleCircuitBreakerConfiguration;
import com.xjjk.agent.aftersale.domain.AfterSaleDetailResult;
import com.xjjk.agent.aftersale.domain.AfterSaleIdentifierType;
import com.xjjk.agent.aftersale.domain.AfterSaleSearchResult;
import com.xjjk.agent.aftersale.service.AfterSaleQueryGateway;
import com.xjjk.agent.aftersale.service.AfterSaleServiceUnavailableException;
import com.xjjk.agent.identity.domain.AgentIdentity;
import feign.FeignException;
import feign.RetryableException;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLException;
import java.net.ConnectException;
import java.net.ProtocolException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** 售后服务防腐层：注入可信身份、执行有限重试并验证响应边界。 */
@Slf4j
@Component
public class AfterSaleServiceGateway implements AfterSaleQueryGateway {
    private static final int SUCCESS_CODE = 1000;
    private static final int MAX_ATTEMPTS = 2;
    private static final int MAX_SEARCH_ITEMS = 5;
    private static final int MAX_DETAIL_ITEMS = 20;

    private final AfterSaleClient client;
    private final String internalToken;
    private final CircuitBreaker searchCircuitBreaker;
    private final CircuitBreaker detailCircuitBreaker;

    @Autowired
    public AfterSaleServiceGateway(
            AfterSaleClient client,
            @Value("${integration.aftersale.internal-token}") String internalToken,
            CircuitBreakerFactory<?, ?> factory) {
        this(client, internalToken,
                Objects.requireNonNull(factory, "熔断器工厂不能为空")
                        .create(AfterSaleCircuitBreakerConfiguration.SEARCH),
                factory.create(AfterSaleCircuitBreakerConfiguration.DETAIL));
    }

    /** 单元测试构造器；生产环境始终使用两个独立熔断器。 */
    public AfterSaleServiceGateway(AfterSaleClient client, String internalToken) {
        this(client, internalToken, null, null);
    }

    private AfterSaleServiceGateway(
            AfterSaleClient client,
            String internalToken,
            CircuitBreaker searchCircuitBreaker,
            CircuitBreaker detailCircuitBreaker) {
        this.client = Objects.requireNonNull(client, "售后客户端不能为空");
        if (blank(internalToken)) {
            throw new IllegalArgumentException("integration.aftersale.internal-token 不能为空");
        }
        this.internalToken = internalToken;
        this.searchCircuitBreaker = searchCircuitBreaker;
        this.detailCircuitBreaker = detailCircuitBreaker;
    }

    @Override
    public AfterSaleSearchResult search(
            AfterSaleIdentifierType type,
            String identifier,
            OffsetDateTime startTime,
            OffsetDateTime endTime,
            AgentIdentity identity,
            String requestId) {
        validateSearch(type, identifier, startTime, endTime, identity, requestId);
        Supplier<AfterSaleSearchResult> invocation = () -> mapSearchResponse(
                invokeWithRetry("search", requestId,
                        () -> client.search(
                                internalToken,
                                identity.tenantId(), identity.userId(), identity.orgId(), requestId,
                                new AfterSaleClient.SearchRequest(
                                        type, identifier.strip(), startTime, endTime))));
        return run(searchCircuitBreaker, invocation);
    }

    @Override
    public AfterSaleDetailResult detail(
            String afterSaleCode,
            AgentIdentity identity,
            String requestId) {
        validateCommon(identity, requestId);
        if (blank(afterSaleCode) || afterSaleCode.length() > 128 || unsafe(afterSaleCode)) {
            throw new IllegalArgumentException("售后单号不合法");
        }
        Supplier<AfterSaleDetailResult> invocation = () -> mapDetailResponse(
                invokeWithRetry("detail", requestId,
                        () -> client.detail(
                                internalToken,
                                identity.tenantId(), identity.userId(), identity.orgId(), requestId,
                                new AfterSaleClient.DetailRequest(afterSaleCode.strip()))));
        return run(detailCircuitBreaker, invocation);
    }

    private <T> T run(CircuitBreaker circuitBreaker, Supplier<T> invocation) {
        if (circuitBreaker == null) {
            return invocation.get();
        }
        return circuitBreaker.run(invocation, failure -> {
            throw new AfterSaleServiceUnavailableException("售后服务调用失败");
        });
    }

    /** 仅网络瞬时故障及 502/503/504 重试一次，业务与权限错误绝不重试。 */
    private <T> T invokeWithRetry(
            String operation,
            String requestId,
            Supplier<T> invocation) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return invocation.get();
            } catch (RuntimeException exception) {
                boolean retryable = transientFailure(exception);
                if (!retryable || attempt == MAX_ATTEMPTS) {
                    log.warn("after_sale_gateway requestId={}, operation={}, attempt={}, status=FAILED, failureCategory={}",
                            requestId, operation, attempt, failureCategory(exception));
                    // 主动切断原始 Feign 异常链，避免下游正文和内部请求头泄漏到业务层。
                    throw new AfterSaleServiceUnavailableException("售后服务调用失败");
                }
                log.warn("after_sale_gateway requestId={}, operation={}, attempt={}, status=RETRYING, failureCategory={}",
                        requestId, operation, attempt, failureCategory(exception));
            }
        }
        throw new IllegalStateException("unreachable");
    }

    private boolean transientFailure(RuntimeException exception) {
        if (exception instanceof FeignException feign && feign.status() > 0) {
            return feign.status() == 502 || feign.status() == 503 || feign.status() == 504;
        }
        if (!(exception instanceof RetryableException retryable) || retryable.status() >= 0) {
            return false;
        }
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = retryable.getCause();
             cause != null && visited.add(cause);
             cause = cause.getCause()) {
            if (cause instanceof UnknownHostException || cause instanceof SSLException
                    || cause instanceof ProtocolException) {
                return false;
            }
            if (cause instanceof ConnectException || cause instanceof SocketTimeoutException
                    || cause instanceof ConnectionRequestTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private String failureCategory(RuntimeException exception) {
        if (exception instanceof FeignException feign && feign.status() > 0) {
            return "HTTP_" + feign.status();
        }
        return transientFailure(exception) ? "TRANSIENT_NETWORK" : "NON_RETRYABLE";
    }

    private AfterSaleSearchResult mapSearchResponse(
            AfterSaleServiceResponse<AfterSaleClient.SearchData> response) {
        AfterSaleClient.SearchData data = successfulData(response);
        if (data.total() == null || data.total() < 0 || data.truncated() == null
                || data.queriedAt() == null || data.items() == null
                || data.items().size() > MAX_SEARCH_ITEMS
                || data.total() < data.items().size()
                || (!data.truncated() && data.total() != data.items().size())) {
            throw invalidResponse();
        }
        AfterSaleIdentifierType matchedBy;
        try {
            matchedBy = AfterSaleIdentifierType.valueOf(data.matchedBy());
        } catch (RuntimeException exception) {
            throw invalidResponse();
        }
        List<AfterSaleSearchResult.Item> items = new ArrayList<>(data.items().size());
        for (AfterSaleClient.SearchItemData item : data.items()) {
            items.add(mapSearchItem(item));
        }
        return new AfterSaleSearchResult(
                matchedBy, data.total(), data.truncated(), data.queriedAt(), List.copyOf(items));
    }

    private AfterSaleSearchResult.Item mapSearchItem(AfterSaleClient.SearchItemData item) {
        if (item == null || blank(item.afterSaleCode()) || item.statusCode() == null
                || item.createdAt() == null || item.finished() == null
                || unsafe(item.afterSaleCode()) || unsafe(item.statusText())) {
            throw invalidResponse();
        }
        String statusText = statusText(item.statusText(), item.statusCode(), "search");
        return new AfterSaleSearchResult.Item(
                cleanRequired(item.afterSaleCode()), item.statusCode(), statusText,
                item.createdAt(), item.finished(), item.finishedAt(),
                cleanOptional(item.customerDisplayName()), cleanOptional(item.customerCode()),
                cleanOptional(item.orderCode()), cleanOptional(item.returnLogisticsCode()),
                cleanOptional(item.assigneeDisplayName()));
    }

    private AfterSaleDetailResult mapDetailResponse(
            AfterSaleServiceResponse<AfterSaleClient.DetailData> response) {
        AfterSaleClient.DetailData data = successfulData(response);
        if (blank(data.afterSaleCode()) || data.statusCode() == null
                || data.createdAt() == null || data.finished() == null
                || data.items() == null || data.exchangeGoods() == null
                || data.refundSummary() == null
                || data.itemsTruncated() == null || data.exchangeGoodsTruncated() == null
                || data.queriedAt() == null
                || data.items().size() > MAX_DETAIL_ITEMS
                || data.exchangeGoods().size() > MAX_DETAIL_ITEMS) {
            throw invalidResponse();
        }
        List<AfterSaleDetailResult.Item> items = data.items().stream()
                .map(this::mapDetailItem).toList();
        List<AfterSaleDetailResult.ExchangeGoods> exchangeGoods = data.exchangeGoods().stream()
                .map(this::mapExchangeGoods).toList();
        AfterSaleClient.RefundSummaryData refund = data.refundSummary();
        requireNonNegative(refund.totalCashInFen(), refund.totalPreStorageInFen(),
                refund.returnPreStorageInFen(), refund.returnCouponInFen(), refund.returnIntegral());
        return new AfterSaleDetailResult(
                cleanRequired(data.afterSaleCode()), data.statusCode(),
                statusText(data.statusText(), data.statusCode(), "detail"),
                data.createdAt(), data.finished(), data.finishedAt(),
                cleanOptional(data.customerDisplayName()), cleanOptional(data.customerCode()),
                cleanOptional(data.orderCode()), cleanOptional(data.exchangeOrderCode()),
                cleanOptional(data.returnLogisticsCode()), cleanOptional(data.returnRemark()),
                items, exchangeGoods,
                new AfterSaleDetailResult.RefundSummary(
                        refund.totalCashInFen(), refund.totalPreStorageInFen(),
                        refund.returnPreStorageInFen(), refund.returnCouponInFen(),
                        refund.returnIntegral()),
                data.itemsTruncated(), data.exchangeGoodsTruncated(), data.queriedAt());
    }

    private AfterSaleDetailResult.Item mapDetailItem(AfterSaleClient.ItemData item) {
        if (item == null || blank(item.goodsName()) || blank(item.skuCode())) {
            throw invalidResponse();
        }
        requireNonNegative(item.originalQuantity(), item.receivedQuantity(), item.refundQuantity(),
                item.exchangeQuantity(), item.sentBackQuantity());
        return new AfterSaleDetailResult.Item(
                cleanRequired(item.goodsName()), cleanRequired(item.skuCode()),
                cleanOptional(item.specification()), cleanOptional(item.reason()),
                item.originalQuantity(), item.receivedQuantity(), item.refundQuantity(),
                item.exchangeQuantity(), item.sentBackQuantity());
    }

    private AfterSaleDetailResult.ExchangeGoods mapExchangeGoods(
            AfterSaleClient.ExchangeGoodsData item) {
        if (item == null || blank(item.goodsName()) || blank(item.skuCode())) {
            throw invalidResponse();
        }
        requireNonNegative(item.quantity(), item.unitPriceInFen(), item.subtotalInFen());
        return new AfterSaleDetailResult.ExchangeGoods(
                cleanRequired(item.goodsName()), cleanRequired(item.skuCode()),
                item.quantity(), item.unitPriceInFen(), item.subtotalInFen());
    }

    private <T> T successfulData(AfterSaleServiceResponse<T> response) {
        if (response == null || response.code() == null
                || response.code() != SUCCESS_CODE || response.data() == null) {
            throw invalidResponse();
        }
        return response.data();
    }

    private String statusText(String value, int statusCode, String operation) {
        if (blank(value)) {
            log.warn("after_sale_contract_drift operation={}, statusCode={}", operation, statusCode);
            return "状态未知";
        }
        return cleanRequired(value);
    }

    private void validateSearch(
            AfterSaleIdentifierType type,
            String identifier,
            OffsetDateTime startTime,
            OffsetDateTime endTime,
            AgentIdentity identity,
            String requestId) {
        validateCommon(identity, requestId);
        if (type == null || blank(identifier) || identifier.length() > 128 || unsafe(identifier)
                || (type == AfterSaleIdentifierType.CUSTOMER_NAME
                && identifier.strip().codePointCount(0, identifier.strip().length()) < 2)
                || (startTime != null && endTime != null && startTime.isAfter(endTime))) {
            throw new IllegalArgumentException("售后查询参数不合法");
        }
    }

    private void validateCommon(AgentIdentity identity, String requestId) {
        if (identity == null || identity.userId() <= 0 || identity.orgId() <= 0
                || identity.tenantId() <= 0 || blank(requestId)) {
            throw new IllegalArgumentException("可信坐席身份不完整");
        }
        try {
            UUID.fromString(requestId);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("requestId 必须是 UUID");
        }
    }

    private void requireNonNegative(Number... values) {
        for (Number value : values) {
            if (value != null && value.longValue() < 0) {
                throw invalidResponse();
            }
        }
    }

    private String cleanRequired(String value) {
        if (blank(value) || unsafe(value) || value.length() > 512) {
            throw invalidResponse();
        }
        return value.strip();
    }

    private String cleanOptional(String value) {
        if (value == null) {
            return null;
        }
        if (unsafe(value) || value.length() > 2048) {
            throw invalidResponse();
        }
        return value.strip();
    }

    private boolean unsafe(String value) {
        return value != null && value.codePoints().anyMatch(Character::isISOControl);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private AfterSaleServiceUnavailableException invalidResponse() {
        return new AfterSaleServiceUnavailableException("售后服务响应不可用");
    }
}
