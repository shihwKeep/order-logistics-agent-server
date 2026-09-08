package com.xjjk.agent.aftersale.client;

import com.xjjk.agent.aftersale.domain.AfterSaleIdentifierType;
import com.xjjk.agent.aftersale.service.AfterSaleServiceUnavailableException;
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
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AfterSaleServiceGatewayTest {
    private static final String REQUEST_ID = "b8e3115a-235c-431e-8f1a-147bb54fa852";
    private static final AgentIdentity IDENTITY =
            new AgentIdentity(10567L, "10567", "测试坐席", 20L, 1L);

    @Test
    void searchPassesTrustedIdentityAndMapsFiveItems() {
        AtomicReference<Headers> captured = new AtomicReference<>();
        AfterSaleClient client = new StubClient() {
            @Override
            public AfterSaleServiceResponse<AfterSaleClient.SearchData> search(
                    String token, long tenantId, long userId, long orgId,
                    String requestId, AfterSaleClient.SearchRequest request) {
                captured.set(new Headers(token, tenantId, userId, orgId, requestId));
                return success(searchData(6L, true, 5));
            }
        };

        var result = new AfterSaleServiceGateway(client, "secret-token").search(
                AfterSaleIdentifierType.CUSTOMER_CODE, "C24101816040001",
                null, null, IDENTITY, REQUEST_ID);

        assertThat(captured.get()).isEqualTo(
                new Headers("secret-token", 1L, 10567L, 20L, REQUEST_ID));
        assertThat(result.total()).isEqualTo(6L);
        assertThat(result.items()).hasSize(5);
    }

    @Test
    void retriesHttp503Once() {
        AtomicInteger attempts = new AtomicInteger();
        AfterSaleClient client = new StubClient() {
            @Override
            public AfterSaleServiceResponse<AfterSaleClient.SearchData> search(
                    String token, long tenantId, long userId, long orgId,
                    String requestId, AfterSaleClient.SearchRequest request) {
                if (attempts.incrementAndGet() == 1) {
                    throw http(503);
                }
                return success(searchData(0L, false, 0));
            }
        };

        new AfterSaleServiceGateway(client, "secret-token").search(
                AfterSaleIdentifierType.ORDER_CODE, "XJTS01",
                null, null, IDENTITY, REQUEST_ID);

        assertThat(attempts).hasValue(2);
    }

    @Test
    void doesNotRetryHttp400() {
        AtomicInteger attempts = new AtomicInteger();
        AfterSaleClient client = new StubClient() {
            @Override
            public AfterSaleServiceResponse<AfterSaleClient.SearchData> search(
                    String token, long tenantId, long userId, long orgId,
                    String requestId, AfterSaleClient.SearchRequest request) {
                attempts.incrementAndGet();
                throw http(400);
            }
        };

        assertThatThrownBy(() -> new AfterSaleServiceGateway(client, "secret-token").search(
                AfterSaleIdentifierType.ORDER_CODE, "XJTS01",
                null, null, IDENTITY, REQUEST_ID))
                .isInstanceOf(AfterSaleServiceUnavailableException.class)
                .hasNoCause();
        assertThat(attempts).hasValue(1);
    }

    @Test
    void rejectsMoreThanFiveSearchItems() {
        AfterSaleClient client = new StubClient() {
            @Override
            public AfterSaleServiceResponse<AfterSaleClient.SearchData> search(
                    String token, long tenantId, long userId, long orgId,
                    String requestId, AfterSaleClient.SearchRequest request) {
                return success(searchData(6L, false, 6));
            }
        };

        assertThatThrownBy(() -> new AfterSaleServiceGateway(client, "secret-token").search(
                AfterSaleIdentifierType.ORDER_CODE, "XJTS01",
                null, null, IDENTITY, REQUEST_ID))
                .isInstanceOf(AfterSaleServiceUnavailableException.class)
                .hasNoCause();
    }

    @Test
    void detailRejectsMoreThanTwentyItems() {
        AfterSaleClient client = new StubClient() {
            @Override
            public AfterSaleServiceResponse<AfterSaleClient.DetailData> detail(
                    String token, long tenantId, long userId, long orgId,
                    String requestId, AfterSaleClient.DetailRequest request) {
                return success(detailData(21));
            }
        };

        assertThatThrownBy(() -> new AfterSaleServiceGateway(client, "secret-token")
                .detail("AS001", IDENTITY, REQUEST_ID))
                .isInstanceOf(AfterSaleServiceUnavailableException.class)
                .hasNoCause();
    }

    private AfterSaleClient.SearchData searchData(long total, boolean truncated, int size) {
        return new AfterSaleClient.SearchData(
                "CUSTOMER_CODE", total, truncated, OffsetDateTime.now(),
                IntStream.range(0, size).mapToObj(this::searchItem).toList());
    }

    private AfterSaleClient.SearchItemData searchItem(int index) {
        return new AfterSaleClient.SearchItemData(
                "AS" + index, 1, "处理中", OffsetDateTime.now(), false, null,
                "张*", "C001", "XJTS01", null, "李*");
    }

    private AfterSaleClient.DetailData detailData(int itemCount) {
        List<AfterSaleClient.ItemData> items = IntStream.range(0, itemCount)
                .mapToObj(index -> new AfterSaleClient.ItemData(
                        "商品", "SKU" + index, "1盒", "破损", 1, 1, 1, 0, 0))
                .toList();
        return new AfterSaleClient.DetailData(
                "AS001", 1, "处理中", OffsetDateTime.now(), false, null,
                "张*", "C001", "XJTS01", null, null, null,
                items, List.of(), new AfterSaleClient.RefundSummaryData(0L, 0L, 0L, 0L, 0L),
                true, false, OffsetDateTime.now());
    }

    private <T> AfterSaleServiceResponse<T> success(T data) {
        return new AfterSaleServiceResponse<>(1000, "成功", data);
    }

    private FeignException http(int status) {
        Request request = Request.create(Request.HttpMethod.POST, "/after-sales", Map.of(),
                null, StandardCharsets.UTF_8, null);
        Response response = Response.builder().status(status).request(request).build();
        return FeignException.errorStatus("after-sales", response);
    }

    private abstract static class StubClient implements AfterSaleClient {
        @Override
        public AfterSaleServiceResponse<AfterSaleClient.SearchData> search(
                String token, long tenantId, long userId, long orgId,
                String requestId, AfterSaleClient.SearchRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AfterSaleServiceResponse<AfterSaleClient.DetailData> detail(
                String token, long tenantId, long userId, long orgId,
                String requestId, AfterSaleClient.DetailRequest request) {
            throw new UnsupportedOperationException();
        }
    }

    private record Headers(String token, long tenantId, long userId, long orgId,
                           String requestId) {
    }
}
