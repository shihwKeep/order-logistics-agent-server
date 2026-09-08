package com.xjjk.agent.customer.service;

import com.xjjk.agent.customer.domain.CustomerMatchType;
import com.xjjk.agent.customer.domain.CustomerSearchItem;
import com.xjjk.agent.customer.domain.CustomerSearchResult;
import com.xjjk.agent.identity.domain.AgentIdentity;
import com.xjjk.agent.order.domain.OrderIdentifierType;
import com.xjjk.agent.order.domain.OrderLogisticsResult;
import com.xjjk.agent.order.domain.OrderSearchResult;
import com.xjjk.agent.order.service.OrderQueryGateway;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerOrderQueryServiceTest {
    private static final AgentIdentity IDENTITY =
            new AgentIdentity(10567L, "10567", "测试坐席", 23L, 1L);

    @Test
    void resolvesAuthorizedCustomerCodeBeforeQueryingOrdersByInternalId() {
        CustomerSearchItem customer = new CustomerSearchItem(
                80001L, "C001", "张*", "金卡", "自有", "普通客户");
        CustomerQueryGateway customers = (keyword, type, identity, requestId) ->
                new CustomerSearchResult(type, 1, false, OffsetDateTime.now(), List.of(customer));
        AtomicLong queriedCustomerId = new AtomicLong();
        OrderSearchResult orders = new OrderSearchResult(
                OrderIdentifierType.CUSTOMER, 0, false, OffsetDateTime.now(), List.of());
        OrderQueryGateway orderGateway = new RecordingOrderGateway(queriedCustomerId, orders);

        CustomerOrderQueryResult result = new CustomerOrderQueryService(customers, orderGateway)
                .query(" C001 ", IDENTITY, "request-1");

        assertThat(queriedCustomerId).hasValue(80001L);
        assertThat(result.customerCode()).isEqualTo("C001");
        assertThat(result.customerDisplayName()).isEqualTo("张*");
        assertThat(result.orders()).isSameAs(orders);
        assertThat(result.getClass().getRecordComponents())
                .extracting(component -> component.getName())
                .doesNotContain("customerId");
    }

    @Test
    void stopsBeforeOrderServiceWhenCustomerIsMissingOrAmbiguous() {
        AtomicLong queriedCustomerId = new AtomicLong();
        OrderQueryGateway orders = new RecordingOrderGateway(queriedCustomerId, null);

        CustomerQueryGateway missing = (keyword, type, identity, requestId) ->
                new CustomerSearchResult(type, 0, false, OffsetDateTime.now(), List.of());
        assertThat(new CustomerOrderQueryService(missing, orders)
                .query("C404", IDENTITY, "request-1").resolution())
                .isEqualTo(CustomerOrderResolution.NOT_FOUND);

        CustomerSearchItem first = new CustomerSearchItem(
                1L, "C001", "张*", null, null, null);
        CustomerSearchItem second = new CustomerSearchItem(
                2L, "C001", "李*", null, null, null);
        CustomerQueryGateway ambiguous = (keyword, type, identity, requestId) ->
                new CustomerSearchResult(type, 2, false, OffsetDateTime.now(), List.of(first, second));
        assertThat(new CustomerOrderQueryService(ambiguous, orders)
                .query("C001", IDENTITY, "request-2").resolution())
                .isEqualTo(CustomerOrderResolution.AMBIGUOUS);

        assertThat(queriedCustomerId).hasValue(0L);
    }

    private record RecordingOrderGateway(
            AtomicLong customerId,
            OrderSearchResult result) implements OrderQueryGateway {
        @Override
        public OrderSearchResult search(String identifier, OrderIdentifierType identifierType,
                                        AgentIdentity identity, String requestId) {
            throw new AssertionError("不应按业务编号查询");
        }

        @Override
        public OrderSearchResult searchByCustomerId(long value, AgentIdentity identity,
                                                    String requestId) {
            customerId.set(value);
            return result;
        }

        @Override
        public OrderLogisticsResult logistics(String identifier, OrderIdentifierType identifierType,
                                              AgentIdentity identity, String requestId) {
            throw new AssertionError("不应查询物流");
        }
    }
}
