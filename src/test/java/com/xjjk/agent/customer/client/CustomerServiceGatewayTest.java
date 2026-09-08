package com.xjjk.agent.customer.client;

import com.xjjk.agent.customer.domain.CustomerMatchType;
import com.xjjk.agent.customer.service.CustomerServiceUnavailableException;
import com.xjjk.agent.identity.domain.AgentIdentity;
import feign.FeignException;
import feign.Request;
import feign.Response;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomerServiceGatewayTest {
    private static final AgentIdentity IDENTITY =
            new AgentIdentity(10567L, "10567", "坐席", 23L, 1L);

    @Test
    void sendsTrustedHeadersAndMapsSafeCustomerResult() {
        AtomicReference<Headers> captured = new AtomicReference<>();
        CustomerSearchClient client = (token, tenant, user, org, requestId, request) -> {
            captured.set(new Headers(token, tenant, user, org, requestId));
            assertThat(request).isEqualTo(new CustomerSearchClient.SearchRequest(
                    "C001", CustomerMatchType.CUSTOMER_CODE));
            return success();
        };
        CustomerServiceGateway gateway = new CustomerServiceGateway(client, "secret-token");

        var result = gateway.search("C001", CustomerMatchType.CUSTOMER_CODE,
                IDENTITY, "request-1");

        assertThat(captured.get()).isEqualTo(
                new Headers("secret-token", 1L, 10567L, 23L, "request-1"));
        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.customerId()).isEqualTo(80001L);
            assertThat(item.customerCode()).isEqualTo("C001");
            assertThat(item.displayName()).isEqualTo("张*");
        });
    }

    @Test
    void retriesOnlyTransientGatewayStatusOnce() {
        AtomicInteger attempts = new AtomicInteger();
        CustomerSearchClient client = (token, tenant, user, org, requestId, request) -> {
            if (attempts.incrementAndGet() == 1) {
                throw http(503);
            }
            return success();
        };

        new CustomerServiceGateway(client, "secret-token").search(
                "C001", CustomerMatchType.AUTO, IDENTITY, "request-2");

        assertThat(attempts).hasValue(2);
    }

    @Test
    void rejectsMalformedOrOversizedResponseWithoutRetry() {
        AtomicInteger attempts = new AtomicInteger();
        CustomerSearchClient client = (token, tenant, user, org, requestId, request) -> {
            attempts.incrementAndGet();
            return new CustomerServiceResponse<>(1000, "成功", new CustomerSearchClient.SearchData(
                    "CUSTOMER_NAME", 11L, true, OffsetDateTime.now(),
                    java.util.stream.IntStream.range(0, 11)
                            .mapToObj(index -> item((long) index + 1, "C" + index)).toList()));
        };

        assertThatThrownBy(() -> new CustomerServiceGateway(client, "secret-token").search(
                "张三", CustomerMatchType.AUTO, IDENTITY, "request-3"))
                .isInstanceOf(CustomerServiceUnavailableException.class);
        assertThat(attempts).hasValue(1);
    }

    private CustomerServiceResponse<CustomerSearchClient.SearchData> success() {
        return new CustomerServiceResponse<>(1000, "成功", new CustomerSearchClient.SearchData(
                "CUSTOMER_CODE", 1L, false, OffsetDateTime.now(),
                List.of(item(80001L, "C001"))));
    }

    private CustomerSearchClient.CustomerData item(long id, String code) {
        return new CustomerSearchClient.CustomerData(
                id, code, "张*", "金卡", "自有", "普通客户");
    }

    private FeignException http(int status) {
        Request request = Request.create(Request.HttpMethod.POST, "/customers", Map.of(),
                null, StandardCharsets.UTF_8, null);
        Response response = Response.builder().status(status).request(request).build();
        return FeignException.errorStatus("search", response);
    }

    private record Headers(String token, long tenant, long user, long org, String requestId) {
    }
}
