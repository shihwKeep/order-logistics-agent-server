package com.xjjk.agent.chat.orchestration;

import com.xjjk.agent.customer.service.CustomerOrderQueryResult;
import com.xjjk.agent.customer.service.CustomerOrderResolution;
import com.xjjk.agent.order.domain.OrderCard;
import com.xjjk.agent.order.domain.OrderIdentifierType;
import com.xjjk.agent.order.domain.OrderSearchResult;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LatestOrderResolverTest {

    @Test
    void selectsNewestPublicOrderCodeAndUsesStableTieBreak() {
        OrderSearchResult orders = result(
                card("XJ002", "2026-08-20 10:00:00"),
                card("XJ001", "2026-08-20 10:00:00"),
                card("XJ003", "2026-08-19 10:00:00"));

        assertThat(new LatestOrderResolver().resolve(found(orders)))
                .contains(new LatestOrderResolver.ResolvedOrder(
                        "XJ001", "2026-08-20 10:00:00"));
    }

    @Test
    void ignoresBlankCodesAndInvalidTimesWhenAValidOrderExists() {
        OrderSearchResult orders = result(
                card("", "2026-12-31 10:00:00"),
                card("XJ001", "not-a-time"),
                card("XJ002", "2026-08-20 10:00:00"));

        assertThat(new LatestOrderResolver().resolve(found(orders)))
                .contains(new LatestOrderResolver.ResolvedOrder(
                        "XJ002", "2026-08-20 10:00:00"));
    }

    @Test
    void returnsEmptyForNotFoundOrMissingOrders() {
        LatestOrderResolver resolver = new LatestOrderResolver();

        assertThat(resolver.resolve(new CustomerOrderQueryResult(
                CustomerOrderResolution.NOT_FOUND, "C1", null, null))).isEmpty();
        assertThat(resolver.resolve(found(result()))).isEmpty();
    }

    private static CustomerOrderQueryResult found(OrderSearchResult orders) {
        return new CustomerOrderQueryResult(
                CustomerOrderResolution.FOUND, "C1", "张*", orders);
    }

    private static OrderSearchResult result(OrderCard... cards) {
        return new OrderSearchResult(
                OrderIdentifierType.CUSTOMER, cards.length, false,
                OffsetDateTime.parse("2026-08-20T12:00:00+08:00"),
                List.of(cards));
    }

    private static OrderCard card(String code, String time) {
        return new OrderCard(
                code, "", 20, "在途", time, "张*", 0L, 0,
                List.of(), "德邦", List.of());
    }
}
