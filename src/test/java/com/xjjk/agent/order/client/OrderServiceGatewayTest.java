package com.xjjk.agent.order.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xjjk.agent.identity.domain.AgentIdentity;
import com.xjjk.agent.order.domain.OrderIdentifierType;
import com.xjjk.agent.order.domain.OrderLogisticsResult;
import com.xjjk.agent.order.domain.OrderSearchResult;
import com.xjjk.agent.order.service.OrderServiceUnavailableException;
import feign.FeignException;
import feign.Request;
import feign.Response;
import feign.RetryableException;
import java.net.ConnectException;
import java.net.ProtocolException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderServiceGatewayTest {
    private static final String TOKEN = "service-secret";
    private static final AgentIdentity IDENTITY =
            new AgentIdentity(10567L, "agent", "坐席", 10L, 1L);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    @Test
    void sendsTrustedIdentityHeadersAndMapsOrderResponseWithoutSensitiveInternalId() {
        AtomicReference<Headers> captured = new AtomicReference<>();
        OrderSearchClient client = (token, tenant, user, org, requestId, request) -> {
            captured.set(new Headers(token, tenant, user, org, requestId));
            assertThat(request).isEqualTo(
                    new OrderSearchClient.OrderSearchRequest("O123", OrderIdentifierType.AUTO));
            return searchSuccess();
        };
        OrderServiceGateway gateway = gateway(client, unusedLogisticsClient());

        OrderSearchResult result = gateway.search(
                "O123", OrderIdentifierType.AUTO, IDENTITY, "request-1");

        assertThat(captured.get()).isEqualTo(
                new Headers(TOKEN, 1L, 10567L, 10L, "request-1"));
        assertThat(result.matchedBy()).isEqualTo(OrderIdentifierType.ORDER_CODE);
        assertThat(result.total()).isEqualTo(1);
        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.orderCode()).isEqualTo("O123");
            assertThat(item.payAmountInFen()).isEqualTo(12900L);
            assertThat(item.goods()).singleElement()
                    .satisfies(goods -> assertThat(goods.skuCode()).isEqualTo("SKU001"));
        });
        assertThat(result.items().getFirst().getClass().getRecordComponents())
                .extracting(component -> component.getName())
                .doesNotContain("orderId", "mobile", "address");
        assertThatThrownBy(() -> result.items().add(result.items().getFirst()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.items().getFirst().goods().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest(name = "message field {0} with envelope {1}/{2}")
    @MethodSource("orderEnvelopeVariants")
    void deserializesAllMessageAliasesAndEnvelopeCases(
            String messageField,
            String codeField,
            String dataField) throws Exception {
        String data = """
                {"matchedBy":"ORDER_CODE","total":0,"truncated":false,
                 "queriedAt":"2026-09-07T14:30:00+08:00","items":[],"internalOrderId":99}
                """;
        String expectedMessage = "success-" + messageField;
        String json = "{\"" + codeField + "\":1000,\"" + messageField + "\":\""
                + expectedMessage + "\",\"" + dataField + "\":" + data + "}";

        OrderServiceResponse<OrderSearchClient.OrderSearchData> response = OBJECT_MAPPER.readValue(
                json,
                new TypeReference<>() { });

        assertThat(response.code()).isEqualTo(1000);
        assertThat(response.message()).isEqualTo(expectedMessage);
        assertThat(response.data().items()).isEmpty();
        assertThat(response.data().queriedAt())
                .isEqualTo(OffsetDateTime.parse("2026-09-07T14:30:00+08:00"));
    }

    private static Stream<Arguments> orderEnvelopeVariants() {
        return Stream.of(
                Arguments.of("message", "code", "data"),
                Arguments.of("Message", "Code", "Data"),
                Arguments.of("msg", "code", "data"),
                Arguments.of("Msg", "Code", "Data"));
    }

    @Test
    void mapsLogisticsResponseAndSendsTheSameTrustedHeaders() {
        AtomicReference<Headers> captured = new AtomicReference<>();
        OrderLogisticsClient client = (token, tenant, user, org, requestId, request) -> {
            captured.set(new Headers(token, tenant, user, org, requestId));
            assertThat(request.identifier()).isEqualTo("SF123456");
            assertThat(request.identifierType()).isEqualTo(OrderIdentifierType.LOGISTICS_CODE);
            return logisticsSuccess();
        };
        OrderServiceGateway gateway = gateway(unusedSearchClient(), client);

        OrderLogisticsResult result = gateway.logistics(
                "SF123456", OrderIdentifierType.LOGISTICS_CODE, IDENTITY, "request-2");

        assertThat(captured.get()).isEqualTo(
                new Headers(TOKEN, 1L, 10567L, 10L, "request-2"));
        assertThat(result.order().orderCode()).isEqualTo("O123");
        assertThat(result.shipments()).extracting(shipment -> shipment.logisticsCode())
                .containsExactly("SF123456", "YT987654");
        assertThat(result.shipments().get(0).traces())
                .extracting(node -> node.time() + "|" + node.description())
                .containsExactly(
                        "2026-09-07 10:30:00|快件已到达南京转运中心",
                        "2026-09-07 09:00:00|快件已从南京站发出");
        assertThat(result.shipments().get(1).traces())
                .extracting(node -> node.time() + "|" + node.description())
                .containsExactly(
                        "2026-09-07 08:20:00|包裹到达苏州分拨中心",
                        "2026-09-07 07:10:00|包裹已揽收");
        assertThatThrownBy(() -> result.shipments().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void convertsNullResponseNonSuccessCodeAndNullDataToSafeExceptionWithoutRetry() {
        List<OrderServiceResponse<OrderSearchClient.OrderSearchData>> responses = List.of(
                new OrderServiceResponse<>(1001, "business error", searchSuccess().data()),
                new OrderServiceResponse<>(1000, "success", null));
        for (OrderServiceResponse<OrderSearchClient.OrderSearchData> response : responses) {
            AtomicInteger attempts = new AtomicInteger();
            OrderSearchClient client = (token, tenant, user, org, requestId, request) -> {
                attempts.incrementAndGet();
                return response;
            };
            assertThatThrownBy(() -> gateway(client, unusedLogisticsClient()).search(
                    "O123", OrderIdentifierType.AUTO, IDENTITY, "request-invalid"))
                    .isInstanceOf(OrderServiceUnavailableException.class)
                    .hasMessage("订单服务响应不可用");
            assertThat(attempts).hasValue(1);
        }

        AtomicInteger nullAttempts = new AtomicInteger();
        OrderSearchClient nullClient = (token, tenant, user, org, requestId, request) -> {
            nullAttempts.incrementAndGet();
            return null;
        };
        assertThatThrownBy(() -> gateway(nullClient, unusedLogisticsClient()).search(
                "O123", OrderIdentifierType.AUTO, IDENTITY, "request-null"))
                .isInstanceOf(OrderServiceUnavailableException.class)
                .hasMessage("订单服务响应不可用");
        assertThat(nullAttempts).hasValue(1);
    }

    @Test
    void rejectsInvalidCriticalResponseStructureWithoutRetry() {
        AtomicInteger attempts = new AtomicInteger();
        OrderSearchClient client = (token, tenant, user, org, requestId, request) -> {
            attempts.incrementAndGet();
            return new OrderServiceResponse<>(1000, "success", new OrderSearchClient.OrderSearchData(
                    "ORDER_CODE", 1L, false, OffsetDateTime.now(), List.of(
                    new OrderSearchClient.OrderCardData(
                            " ", "OUT123", 80, "在途", "2026-09-07 12:00:00",
                            "石**", 12900L, 0, List.of(), "顺丰", List.of()))));
        };

        assertThatThrownBy(() -> gateway(client, unusedLogisticsClient()).search(
                "O123", OrderIdentifierType.AUTO, IDENTITY, "request-invalid-data"))
                .isInstanceOf(OrderServiceUnavailableException.class)
                .hasMessage("订单服务响应不可用");
        assertThat(attempts).hasValue(1);
    }

    @ParameterizedTest(name = "reject invalid search collection contract: {0}")
    @MethodSource("invalidSearchCollectionResponses")
    void rejectsInvalidSearchCollectionContract(
            String caseName,
            OrderSearchClient.OrderSearchData invalidData) {
        AtomicInteger attempts = new AtomicInteger();
        OrderSearchClient client = (token, tenant, user, org, requestId, request) -> {
            attempts.incrementAndGet();
            return new OrderServiceResponse<>(1000, "success", invalidData);
        };

        assertThatThrownBy(() -> gateway(client, unusedLogisticsClient()).search(
                "O123", OrderIdentifierType.AUTO, IDENTITY, "request-invalid-collections"))
                .isInstanceOf(OrderServiceUnavailableException.class)
                .hasMessage("订单服务响应不可用");
        assertThat(attempts).hasValue(1);
    }

    private static Stream<Arguments> invalidSearchCollectionResponses() {
        OrderSearchClient.OrderGoodsData goods = orderGoods();
        OrderSearchClient.OrderCardData validCard = orderCard(
                1, List.of(goods), List.of("SF123456"));
        return Stream.of(
                Arguments.of("items-null", searchData(1L, false, null)),
                Arguments.of("total-less-than-items", searchData(
                        1L, false, List.of(validCard, validCard))),
                Arguments.of("untruncated-total-greater-than-items", searchData(
                        2L, false, List.of(validCard))),
                Arguments.of("goods-null", searchData(
                        1L, false, List.of(orderCard(1, null, List.of("SF123456"))))),
                Arguments.of("goods-total-less-than-returned-goods", searchData(
                        1L, false, List.of(orderCard(0, List.of(goods), List.of("SF123456"))))),
                Arguments.of("logistics-codes-null", searchData(
                        1L, false, List.of(orderCard(1, List.of(goods), null)))),
                Arguments.of("logistics-codes-over-limit", searchData(
                        1L, false, List.of(orderCard(
                                1,
                                List.of(goods),
                                Stream.generate(() -> "SF123456").limit(11).toList())))));
    }

    @Test
    void allowsTruncatedPageWithPositiveTotalAndNoItems() {
        OrderSearchClient client = (token, tenant, user, org, requestId, request) ->
                new OrderServiceResponse<>(1000, "success", searchData(1L, true, List.of()));

        OrderSearchResult result = gateway(client, unusedLogisticsClient()).search(
                "O123", OrderIdentifierType.AUTO, IDENTITY, "request-empty-page");

        assertThat(result.total()).isEqualTo(1L);
        assertThat(result.truncated()).isTrue();
        assertThat(result.items()).isEmpty();
    }

    @ParameterizedTest(name = "reject invalid logistics collection contract: {0}")
    @MethodSource("invalidLogisticsCollectionResponses")
    void rejectsInvalidLogisticsCollectionContract(
            String caseName,
            OrderLogisticsClient.OrderLogisticsData invalidData) {
        AtomicInteger attempts = new AtomicInteger();
        OrderLogisticsClient client = (token, tenant, user, org, requestId, request) -> {
            attempts.incrementAndGet();
            return new OrderServiceResponse<>(1000, "success", invalidData);
        };

        assertThatThrownBy(() -> gateway(unusedSearchClient(), client).logistics(
                "SF123456", OrderIdentifierType.LOGISTICS_CODE,
                IDENTITY, "request-invalid-logistics-collections"))
                .isInstanceOf(OrderServiceUnavailableException.class)
                .hasMessage("订单服务响应不可用");
        assertThat(attempts).hasValue(1);
    }

    private static Stream<Arguments> invalidLogisticsCollectionResponses() {
        OrderLogisticsClient.ShipmentData shipment = shipment(List.of());
        return Stream.of(
                Arguments.of("shipments-null", logisticsData(null)),
                Arguments.of("shipments-over-limit", logisticsData(
                        Stream.generate(() -> shipment).limit(11).toList())),
                Arguments.of("traces-null", logisticsData(List.of(shipment(null)))));
    }

    @Test
    void convertsNullCollectionElementToSafeResponseException() {
        OrderSearchClient client = (token, tenant, user, org, requestId, request) ->
                new OrderServiceResponse<>(1000, "success", new OrderSearchClient.OrderSearchData(
                        "ORDER_CODE", 1L, false, OffsetDateTime.now(),
                        Arrays.asList((OrderSearchClient.OrderCardData) null)));

        assertThatThrownBy(() -> gateway(client, unusedLogisticsClient()).search(
                "O123", OrderIdentifierType.AUTO, IDENTITY, "request-null-item"))
                .isInstanceOf(OrderServiceUnavailableException.class)
                .hasMessage("订单服务响应不可用");
    }

    @Test
    void retriesConnectionFailureOnceForSearchAndReturnsSecondResult() {
        AtomicInteger attempts = new AtomicInteger();
        OrderSearchClient client = (token, tenant, user, org, requestId, request) -> {
            if (attempts.incrementAndGet() == 1) {
                throw networkFailure(new ConnectException("connection refused"));
            }
            return searchSuccess();
        };

        OrderSearchResult result = gateway(client, unusedLogisticsClient()).search(
                "O123", OrderIdentifierType.AUTO, IDENTITY, "request-retry");

        assertThat(result.items()).hasSize(1);
        assertThat(attempts).hasValue(2);
    }

    @Test
    void retriesReadTimeoutOnceForLogisticsAndReturnsSecondResult() {
        AtomicInteger attempts = new AtomicInteger();
        OrderLogisticsClient client = (token, tenant, user, org, requestId, request) -> {
            if (attempts.incrementAndGet() == 1) {
                throw networkFailure(new SocketTimeoutException("read timed out"));
            }
            return logisticsSuccess();
        };

        OrderLogisticsResult result = gateway(unusedSearchClient(), client).logistics(
                "SF123456", OrderIdentifierType.LOGISTICS_CODE, IDENTITY, "request-timeout");

        assertThat(result.shipments()).hasSize(2);
        assertThat(attempts).hasValue(2);
    }

    @Test
    void retriesNestedConnectionAndCurrentHttpClientPoolTimeout() {
        List<Exception> retryableCauses = List.of(
                new IllegalStateException("wrapped", new ConnectException("connection refused")),
                new ConnectionRequestTimeoutException("connection pool timeout"));

        for (Exception cause : retryableCauses) {
            AtomicInteger attempts = new AtomicInteger();
            OrderSearchClient client = (token, tenant, user, org, requestId, request) -> {
                if (attempts.incrementAndGet() == 1) {
                    throw networkFailure(cause);
                }
                return searchSuccess();
            };

            assertThat(gateway(client, unusedLogisticsClient()).search(
                    "O123", OrderIdentifierType.AUTO, IDENTITY, "request-current-http"))
                    .isNotNull();
            assertThat(attempts).hasValue(2);
        }
    }

    @Test
    void doesNotRetryNegativeStatusForDnsTlsProtocolOrGenericIoFailures() {
        List<Exception> nonRetryableCauses = List.of(
                new UnknownHostException("private-host.invalid"),
                new SSLException("tls failure"),
                new SSLHandshakeException("certificate failure"),
                new ProtocolException("protocol failure"),
                new org.apache.hc.core5.http.ProtocolException("http protocol failure"),
                new java.io.IOException("write failure"));

        for (Exception cause : nonRetryableCauses) {
            AtomicInteger attempts = new AtomicInteger();
            OrderSearchClient client = (token, tenant, user, org, requestId, request) -> {
                attempts.incrementAndGet();
                throw networkFailure(cause);
            };

            assertThatThrownBy(() -> gateway(client, unusedLogisticsClient()).search(
                    "O123", OrderIdentifierType.AUTO, IDENTITY, "request-non-transient"))
                    .isInstanceOf(OrderServiceUnavailableException.class)
                    .hasMessage("订单服务调用失败");
            assertThat(attempts).hasValue(1);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {502, 503, 504})
    void retriesTransientHttpStatusOnceForLogistics(int status) {
        AtomicInteger attempts = new AtomicInteger();
        OrderLogisticsClient client = (token, tenant, user, org, requestId, request) -> {
            if (attempts.incrementAndGet() == 1) {
                throw httpFailure(status);
            }
            return logisticsSuccess();
        };

        OrderLogisticsResult result = gateway(unusedSearchClient(), client).logistics(
                "SF123456", OrderIdentifierType.LOGISTICS_CODE, IDENTITY, "request-retry-logistics");

        assertThat(result.shipments()).hasSize(2);
        assertThat(attempts).hasValue(2);
    }

    @Test
    void retriesTransientHttpStatusEvenWhenFeignRepresentsRetryAfterAsRetryableException() {
        AtomicInteger attempts = new AtomicInteger();
        OrderSearchClient client = (token, tenant, user, org, requestId, request) -> {
            if (attempts.incrementAndGet() == 1) {
                throw new RetryableException(
                        503,
                        "service unavailable",
                        Request.HttpMethod.POST,
                        0L,
                        request());
            }
            return searchSuccess();
        };

        OrderSearchResult result = gateway(client, unusedLogisticsClient()).search(
                "O123", OrderIdentifierType.AUTO, IDENTITY, "request-retry-after");

        assertThat(result.items()).hasSize(1);
        assertThat(attempts).hasValue(2);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 409, 422, 500})
    void doesNotRetryNonTransientHttpStatus(int status) {
        AtomicInteger attempts = new AtomicInteger();
        OrderSearchClient client = (token, tenant, user, org, requestId, request) -> {
            attempts.incrementAndGet();
            throw httpFailure(status);
        };

        assertThatThrownBy(() -> gateway(client, unusedLogisticsClient()).search(
                "O123", OrderIdentifierType.AUTO, IDENTITY, "request-no-retry"))
                .isInstanceOf(OrderServiceUnavailableException.class)
                .hasMessage("订单服务调用失败");
        assertThat(attempts).hasValue(1);
    }

    @Test
    void stopsAfterExactlyTwoTransientAttempts() {
        AtomicInteger attempts = new AtomicInteger();
        OrderLogisticsClient client = (token, tenant, user, org, requestId, request) -> {
            attempts.incrementAndGet();
            throw httpFailure(503);
        };

        assertThatThrownBy(() -> gateway(unusedSearchClient(), client).logistics(
                "SF123456", OrderIdentifierType.LOGISTICS_CODE, IDENTITY, "request-exhausted"))
                .isInstanceOf(OrderServiceUnavailableException.class)
                .hasMessage("订单服务调用失败");
        assertThat(attempts).hasValue(2);
    }

    @Test
    void cutsOffDownstreamExceptionCauseAndSensitiveResponseBody() {
        AtomicInteger attempts = new AtomicInteger();
        OrderSearchClient client = (token, tenant, user, org, requestId, request) -> {
            attempts.incrementAndGet();
            throw httpFailure(500, "sensitive-downstream-body");
        };

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() ->
                gateway(client, unusedLogisticsClient()).search(
                        "O123", OrderIdentifierType.AUTO, IDENTITY, "request-sensitive"));

        assertThat(thrown)
                .isInstanceOf(OrderServiceUnavailableException.class)
                .hasMessage("订单服务调用失败")
                .hasNoCause();
        assertThat(thrown.toString()).doesNotContain("sensitive-downstream-body");
        assertThat(thrown.getSuppressed()).isEmpty();
        assertThat(attempts).hasValue(1);
    }

    @Test
    void rejectsBlankInternalTokenAtStartupBoundary() {
        assertThatThrownBy(() -> new OrderServiceGateway(
                unusedSearchClient(), unusedLogisticsClient(), "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("integration.order.internal-token");
    }

    private static OrderServiceGateway gateway(
            OrderSearchClient searchClient,
            OrderLogisticsClient logisticsClient) {
        return new OrderServiceGateway(searchClient, logisticsClient, TOKEN);
    }

    private static OrderServiceResponse<OrderSearchClient.OrderSearchData> searchSuccess() {
        return new OrderServiceResponse<>(1000, "success", new OrderSearchClient.OrderSearchData(
                "ORDER_CODE", 1L, false, OffsetDateTime.parse("2026-09-07T14:30:00+08:00"),
                List.of(orderCard(1, List.of(orderGoods()), List.of("SF123456")))));
    }

    private static OrderSearchClient.OrderSearchData searchData(
            long total,
            boolean truncated,
            List<OrderSearchClient.OrderCardData> items) {
        return new OrderSearchClient.OrderSearchData(
                "ORDER_CODE",
                total,
                truncated,
                OffsetDateTime.parse("2026-09-07T14:30:00+08:00"),
                items);
    }

    private static OrderSearchClient.OrderCardData orderCard(
            int goodsTotalCount,
            List<OrderSearchClient.OrderGoodsData> goods,
            List<String> logisticsCodes) {
        return new OrderSearchClient.OrderCardData(
                "O123", "OUT123", 80, "在途", "2026-09-07 12:00:00", "石**",
                12900L, goodsTotalCount, goods, "顺丰速运", logisticsCodes);
    }

    private static OrderSearchClient.OrderGoodsData orderGoods() {
        return new OrderSearchClient.OrderGoodsData("商品名称", "SKU001", "规格", 2);
    }

    private static OrderServiceResponse<OrderLogisticsClient.OrderLogisticsData> logisticsSuccess() {
        return new OrderServiceResponse<>(1000, "success", new OrderLogisticsClient.OrderLogisticsData(
                new OrderLogisticsClient.OrderSummaryData("O123", 80, "在途"),
                OffsetDateTime.parse("2026-09-07T14:30:00+08:00"), false,
                List.of(
                        new OrderLogisticsClient.ShipmentData(
                                "SF123456", "顺丰速运", "SUCCESS", "运输中", "已到达南京",
                                List.of(
                                        new OrderLogisticsClient.TrackNodeData(
                                                "2026-09-07 10:30:00", "南京市", "快件已到达南京转运中心"),
                                        new OrderLogisticsClient.TrackNodeData(
                                                "2026-09-07 09:00:00", "南京市", "快件已从南京站发出"))),
                        new OrderLogisticsClient.ShipmentData(
                                "YT987654", "圆通速递", "SUCCESS", "运输中", "已到达苏州",
                                List.of(
                                        new OrderLogisticsClient.TrackNodeData(
                                                "2026-09-07 08:20:00", "苏州市", "包裹到达苏州分拨中心"),
                                        new OrderLogisticsClient.TrackNodeData(
                                                "2026-09-07 07:10:00", "无锡市", "包裹已揽收"))))));
    }

    private static OrderLogisticsClient.OrderLogisticsData logisticsData(
            List<OrderLogisticsClient.ShipmentData> shipments) {
        return new OrderLogisticsClient.OrderLogisticsData(
                new OrderLogisticsClient.OrderSummaryData("O123", 80, "在途"),
                OffsetDateTime.parse("2026-09-07T14:30:00+08:00"),
                false,
                shipments);
    }

    private static OrderLogisticsClient.ShipmentData shipment(
            List<OrderLogisticsClient.TrackNodeData> traces) {
        return new OrderLogisticsClient.ShipmentData(
                "SF123456", "顺丰速运", "SUCCESS", "运输中", "已到达南京", traces);
    }

    private static OrderSearchClient unusedSearchClient() {
        return (token, tenant, user, org, requestId, request) -> {
            throw new AssertionError("unexpected search call");
        };
    }

    private static OrderLogisticsClient unusedLogisticsClient() {
        return (token, tenant, user, org, requestId, request) -> {
            throw new AssertionError("unexpected logistics call");
        };
    }

    private static RetryableException networkFailure(Exception cause) {
        return new RetryableException(
                -1, "network failure", Request.HttpMethod.POST, cause, (Long) null, request());
    }

    private static FeignException httpFailure(int status) {
        return httpFailure(status, "");
    }

    private static FeignException httpFailure(int status, String body) {
        Response response = Response.builder()
                .status(status)
                .reason("downstream failure")
                .request(request())
                .headers(Map.of())
                .body(body, StandardCharsets.UTF_8)
                .build();
        return FeignException.errorStatus("order", response);
    }

    private static Request request() {
        return Request.create(
                Request.HttpMethod.POST,
                "http://order/internal/agent/orders",
                Map.of(),
                null,
                StandardCharsets.UTF_8,
                null);
    }

    private record Headers(String token, long tenantId, long userId, long orgId, String requestId) {
    }
}
