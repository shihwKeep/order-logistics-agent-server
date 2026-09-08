package com.xjjk.agent.customer.client;

import com.xjjk.agent.customer.config.CustomerIntegrationCircuitBreakerConfiguration;
import com.xjjk.agent.customer.domain.CustomerMatchType;
import com.xjjk.agent.customer.domain.CustomerSearchItem;
import com.xjjk.agent.customer.domain.CustomerSearchResult;
import com.xjjk.agent.customer.service.CustomerQueryGateway;
import com.xjjk.agent.customer.service.CustomerServiceUnavailableException;
import com.xjjk.agent.identity.domain.AgentIdentity;
import feign.FeignException;
import feign.RetryableException;
import lombok.extern.slf4j.Slf4j;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** 客户服务适配器：注入可信身份、执行有限重试并验证安全响应契约。 */
@Slf4j
@Component
public class CustomerServiceGateway implements CustomerQueryGateway {
    private static final int SUCCESS_CODE = 1000;
    private static final int MAX_ATTEMPTS = 2;
    private static final int MAX_ITEMS = 10;

    private final CustomerSearchClient client;
    private final String internalToken;
    private final CircuitBreaker circuitBreaker;

    @Autowired
    public CustomerServiceGateway(CustomerSearchClient client,
                                  @Value("${integration.customer.internal-token}") String internalToken,
                                  CircuitBreakerFactory<?, ?> factory) {
        this(client, internalToken, Objects.requireNonNull(factory, "熔断器工厂不能为空")
                .create(CustomerIntegrationCircuitBreakerConfiguration.CUSTOMER_SEARCH));
    }

    /** 单元测试构造器；生产环境始终使用熔断器构造器。 */
    public CustomerServiceGateway(CustomerSearchClient client, String internalToken) {
        this(client, internalToken, (CircuitBreaker) null);
    }

    private CustomerServiceGateway(CustomerSearchClient client,
                                   String internalToken,
                                   CircuitBreaker circuitBreaker) {
        this.client = Objects.requireNonNull(client, "客户客户端不能为空");
        if (internalToken == null || internalToken.isBlank()) {
            throw new IllegalArgumentException("integration.customer.internal-token 不能为空");
        }
        this.internalToken = internalToken;
        this.circuitBreaker = circuitBreaker;
    }

    @Override
    public CustomerSearchResult search(String keyword,
                                       CustomerMatchType matchType,
                                       AgentIdentity identity,
                                       String requestId) {
        validateRequest(keyword, matchType, identity, requestId);
        Supplier<CustomerSearchResult> invocation = () -> mapResponse(invokeWithRetry(
                matchType, requestId,
                () -> client.search(internalToken, identity.tenantId(), identity.userId(),
                        identity.orgId(), requestId,
                        new CustomerSearchClient.SearchRequest(keyword.strip(), matchType))));
        if (circuitBreaker == null) {
            return invocation.get();
        }
        return circuitBreaker.run(invocation, failure -> {
            throw new CustomerServiceUnavailableException("客户服务调用失败");
        });
    }

    private CustomerServiceResponse<CustomerSearchClient.SearchData> invokeWithRetry(
            CustomerMatchType type,
            String requestId,
            Supplier<CustomerServiceResponse<CustomerSearchClient.SearchData>> invocation) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return invocation.get();
            } catch (RuntimeException exception) {
                if (!transientFailure(exception) || attempt == MAX_ATTEMPTS) {
                    log.warn("customer_gateway requestId={}, matchType={}, attempt={}, status=FAILED",
                            requestId, type, attempt);
                    // 切断 Feign 异常链，避免下游正文或内部请求头越过边界。
                    throw new CustomerServiceUnavailableException("客户服务调用失败");
                }
                log.warn("customer_gateway requestId={}, matchType={}, attempt={}, status=RETRYING",
                        requestId, type, attempt);
            }
        }
        throw new IllegalStateException("unreachable");
    }

    private boolean transientFailure(RuntimeException exception) {
        if (exception instanceof FeignException feignException) {
            int status = feignException.status();
            if (status == 502 || status == 503 || status == 504) {
                return true;
            }
        }
        if (!(exception instanceof RetryableException)) {
            return false;
        }
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = exception; cause != null && visited.add(cause); cause = cause.getCause()) {
            if (cause instanceof UnknownHostException || cause instanceof SSLException
                    || cause instanceof ProtocolException) {
                return false;
            }
            if (cause instanceof SocketTimeoutException || cause instanceof ConnectException) {
                return true;
            }
        }
        return false;
    }

    private CustomerSearchResult mapResponse(
            CustomerServiceResponse<CustomerSearchClient.SearchData> response) {
        if (response == null || response.code() == null || response.code() != SUCCESS_CODE
                || response.data() == null) {
            throw invalidResponse();
        }
        CustomerSearchClient.SearchData data = response.data();
        if (data.total() == null || data.total() < 0 || data.truncated() == null
                || data.queriedAt() == null || data.items() == null
                || data.items().size() > MAX_ITEMS || data.total() < data.items().size()
                || (!data.truncated() && data.total() != data.items().size())) {
            throw invalidResponse();
        }
        CustomerMatchType matchedBy;
        try {
            matchedBy = CustomerMatchType.valueOf(data.matchedBy());
        } catch (RuntimeException exception) {
            throw invalidResponse();
        }
        if (matchedBy == CustomerMatchType.AUTO) {
            throw invalidResponse();
        }
        List<CustomerSearchItem> items = new ArrayList<>();
        for (CustomerSearchClient.CustomerData item : data.items()) {
            items.add(mapItem(item));
        }
        return new CustomerSearchResult(matchedBy, data.total(), data.truncated(),
                data.queriedAt(), items);
    }

    private CustomerSearchItem mapItem(CustomerSearchClient.CustomerData item) {
        if (item == null || item.customerId() == null || item.customerId() <= 0
                || blank(item.customerCode()) || blank(item.displayName())
                || blank(item.gradeName()) || blank(item.assetTypeName())
                || blank(item.customerTypeName()) || unsafe(item.customerCode())
                || unsafe(item.displayName())) {
            throw invalidResponse();
        }
        int nameLength = item.displayName().codePointCount(0, item.displayName().length());
        if (nameLength > 1 && item.displayName().indexOf('*') < 0) {
            throw invalidResponse();
        }
        return new CustomerSearchItem(item.customerId(), item.customerCode().strip(),
                item.displayName().strip(), item.gradeName().strip(),
                item.assetTypeName().strip(), item.customerTypeName().strip());
    }

    private void validateRequest(String keyword, CustomerMatchType type,
                                 AgentIdentity identity, String requestId) {
        if (blank(keyword) || keyword.length() > 128 || unsafe(keyword)
                || type == null || identity == null || identity.userId() <= 0
                || identity.orgId() <= 0 || identity.tenantId() <= 0 || blank(requestId)) {
            throw new IllegalArgumentException("客户查询参数不完整");
        }
    }

    private boolean unsafe(String value) {
        return value != null && value.codePoints().anyMatch(Character::isISOControl);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private CustomerServiceUnavailableException invalidResponse() {
        return new CustomerServiceUnavailableException("客户服务响应不可用");
    }
}
