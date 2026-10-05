package com.xjjk.agent.evaluation;

import com.xjjk.agent.customer.service.CustomerOrderQueryResult;
import com.xjjk.agent.customer.service.CustomerOrderResolution;
import com.xjjk.agent.identity.domain.AgentIdentity;
import com.xjjk.agent.knowledge.domain.KnowledgeRetrievalResult;
import com.xjjk.agent.order.domain.OrderCard;
import com.xjjk.agent.order.domain.OrderIdentifierType;
import com.xjjk.agent.order.domain.OrderLogisticsResult;
import com.xjjk.agent.order.domain.OrderSearchResult;
import com.xjjk.agent.order.domain.ShipmentTimeline;
import com.xjjk.agent.order.domain.TrackNode;
import com.xjjk.agent.product.domain.ProductSearchItem;
import com.xjjk.agent.product.domain.ProductSearchResult;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

final class GoldenFixtureFactory {

    private static final OffsetDateTime FIXED_NOW =
            OffsetDateTime.parse("2026-09-30T04:00:00+08:00");

    private GoldenFixtureFactory() {
    }

    static OffsetDateTime fixedNow() {
        return FIXED_NOW;
    }

    static AgentIdentity identity() {
        return new AgentIdentity(10567L, "golden-account", "golden-user", 3673L, 1L);
    }

    static OrderLogisticsResult logisticsNormal() {
        return new OrderLogisticsResult(
                new OrderLogisticsResult.OrderSummary(
                        "ORDER_FIXTURE_001", 20, "在途"),
                FIXED_NOW,
                false,
                List.of(new ShipmentTimeline(
                        "TRACK_FIXTURE_001", "测试承运商", "SUCCESS", "在途",
                        "到达测试转运场",
                        List.of(new TrackNode(
                                "2026-09-29 15:58:06", "测试转运场", "到达测试转运场")))));
    }

    static OrderSearchResult orderNormal() {
        return new OrderSearchResult(
                OrderIdentifierType.ORDER_CODE,
                1,
                false,
                FIXED_NOW,
                List.of(new OrderCard(
                        "ORDER_FIXTURE_001", "OUTER_FIXTURE_001", 20, "在途",
                        "2026-09-29 15:40:21", "测试客户*", 6000L, 1,
                        List.of(), "测试承运商", List.of("TRACK_FIXTURE_001"))));
    }

    static OrderSearchResult orderEmpty() {
        return new OrderSearchResult(
                OrderIdentifierType.ORDER_CODE, 0, false, FIXED_NOW, List.of());
    }

    static ProductSearchResult productNormal() {
        return new ProductSearchResult(
                "PRODUCT_FIXTURE_001", 1, 10, 1, false,
                List.of(new ProductSearchItem(
                        1L, "SPU_FIXTURE_001", "SKU_FIXTURE_001", "测试商品",
                        "测试规格", 11100L, 100L, "ON_SALE", "已上架",
                        null, true)));
    }

    static ProductSearchResult productNotFound() {
        return new ProductSearchResult(
                "PRODUCT_NOT_FOUND", 1, 10, 0, false, List.of());
    }

    static KnowledgeRetrievalResult knowledgePolicy(String question) {
        return new KnowledgeRetrievalResult(
                true,
                List.of(new KnowledgeRetrievalResult.Evidence(
                        1L, 1L, 1L, "CHUNK_FIXTURE_001", "测试规则",
                        "测试规则 > 核心规则", "固定测试证据: " + question,
                        "{}", 0.95, Set.of("golden"))),
                "golden-v1", "NONE", "SUCCESS", FIXED_NOW);
    }

    static CustomerOrderQueryResult customerOrderNormal() {
        return new CustomerOrderQueryResult(
                CustomerOrderResolution.FOUND,
                "CUSTOMER_FIXTURE_001",
                "测试客户*",
                orderNormal());
    }

    static CustomerOrderQueryResult customerOrderNotFound() {
        return new CustomerOrderQueryResult(
                CustomerOrderResolution.NOT_FOUND,
                "CUSTOMER_NOT_FOUND",
                null,
                null);
    }

    static String safeSummary(Object value) {
        if (value instanceof CustomerOrderQueryResult result) {
            return "resolution=" + result.resolution()
                    + ", orderCount="
                    + (result.orders() == null ? 0 : result.orders().total());
        }
        return value == null ? "" : value.getClass().getSimpleName();
    }
}
