package com.xjjk.agent.evaluation;

import com.xjjk.agent.customer.service.CustomerOrderQueryResult;
import com.xjjk.agent.customer.service.CustomerOrderResolution;
import com.xjjk.agent.knowledge.domain.KnowledgeRetrievalResult;
import com.xjjk.agent.order.domain.OrderLogisticsResult;
import com.xjjk.agent.order.domain.OrderSearchResult;
import com.xjjk.agent.product.domain.ProductSearchResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GoldenFixtureFactoryTest {

    @Test
    void buildsStableBusinessFixturesWithFixedTime() {
        assertThat(GoldenFixtureFactory.fixedNow())
                .hasToString("2026-09-30T04:00+08:00");
        assertThat(GoldenFixtureFactory.logisticsNormal().shipments()).hasSize(1);
        assertThat(GoldenFixtureFactory.orderNormal().items()).hasSize(1);
        assertThat(GoldenFixtureFactory.productNormal().items()).hasSize(1);
        assertThat(GoldenFixtureFactory.knowledgePolicy("物流规则").answerable())
                .isTrue();
    }

    @Test
    void buildsSafeNotFoundAndUnavailableFixtures() {
        CustomerOrderQueryResult notFound = GoldenFixtureFactory.customerOrderNotFound();
        assertThat(notFound.resolution()).isEqualTo(CustomerOrderResolution.NOT_FOUND);
        assertThat(notFound.orders()).isNull();
        assertThat(GoldenFixtureFactory.orderEmpty().total()).isZero();
        assertThat(GoldenFixtureFactory.productNotFound().items()).isEmpty();
        assertThat(GoldenFixtureFactory.safeSummary(notFound))
                .doesNotContain("customerId", "Authorization", "Token");
    }
}
