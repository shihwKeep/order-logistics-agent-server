package com.xjjk.agent.aftersale.tool;

import com.xjjk.agent.aftersale.domain.AfterSaleDetailResult;
import com.xjjk.agent.aftersale.domain.AfterSaleIdentifierType;
import com.xjjk.agent.aftersale.domain.AfterSaleSearchResult;
import com.xjjk.agent.aftersale.service.AfterSaleQueryGateway;
import com.xjjk.agent.identity.domain.AgentIdentity;
import com.xjjk.agent.tool.AgentToolRequestContext;
import com.xjjk.agent.tool.ToolCallGuard;
import com.xjjk.agent.tool.ToolUiResult;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AfterSaleQueryToolsTest {
    private static final String REQUEST_ID = "b8e3115a-235c-431e-8f1a-147bb54fa852";
    private static final AgentIdentity IDENTITY =
            new AgentIdentity(10567L, "10567", "坐席", 23L, 1L);

    @Test
    void publishesFullSearchResultButReturnsBoundedModelFacts() {
        AfterSaleSearchResult expected = new AfterSaleSearchResult(
                AfterSaleIdentifierType.CUSTOMER_CODE, 6L, true, OffsetDateTime.now(),
                List.of(new AfterSaleSearchResult.Item(
                        "AS001", 1, "处理中", OffsetDateTime.now(), false, null,
                        "张*", "C001", "XJTS01", null, "李*")));
        AfterSaleQueryGateway gateway = new StubGateway() {
            @Override
            public AfterSaleSearchResult search(
                    AfterSaleIdentifierType type, String identifier,
                    OffsetDateTime startTime, OffsetDateTime endTime,
                    AgentIdentity identity, String requestId) {
                return expected;
            }
        };
        AtomicReference<ToolUiResult> published = new AtomicReference<>();

        String text = new AfterSaleQueryTools(gateway, availability()).searchAfterSales(
                "CUSTOMER_CODE", "C24101816040001", null, null, context(published));

        assertThat(text).contains("匹配总数=6", "AS001", "处理中")
                .hasSizeLessThanOrEqualTo(1900);
        assertThat(published.get().toolName()).isEqualTo("search_after_sales");
        assertThat(published.get().kind()).isEqualTo("after-sale-list");
        assertThat(published.get().data()).isSameAs(expected);
    }

    @Test
    void detailDoesNotExposeRemarkOrReasonToModelText() {
        AfterSaleDetailResult expected = new AfterSaleDetailResult(
                "AS001", 1, "处理中", OffsetDateTime.now(), false, null,
                "张*", "C001", "XJTS01", null, "SF001", "敏感售后说明",
                List.of(new AfterSaleDetailResult.Item(
                        "商品", "SKU001", "1盒", "敏感商品原因", 1, 1, 1, 0, 0)),
                List.of(), new AfterSaleDetailResult.RefundSummary(100L, 0L, 0L, 0L, 0L),
                false, false, OffsetDateTime.now());
        AfterSaleQueryGateway gateway = new StubGateway() {
            @Override
            public AfterSaleDetailResult detail(
                    String afterSaleCode, AgentIdentity identity, String requestId) {
                return expected;
            }
        };
        AtomicReference<ToolUiResult> published = new AtomicReference<>();

        String text = new AfterSaleQueryTools(gateway, availability())
                .getAfterSaleDetail(" AS001 ", context(published));

        assertThat(text).contains("AS001", "商品行数=1", "前端已展示详情")
                .doesNotContain("敏感售后说明", "敏感商品原因")
                .hasSizeLessThanOrEqualTo(1900);
        assertThat(published.get().kind()).isEqualTo("after-sale-detail");
        assertThat(published.get().data()).isSameAs(expected);
    }

    @Test
    void disabledCapabilityDoesNotCallGateway() {
        AfterSaleToolAvailability disabled = new AfterSaleToolAvailability(
                new AfterSaleToolAvailability.Capability(false, "OFF", Set.of()),
                new AfterSaleToolAvailability.Capability(false, "OFF", Set.of()));

        String text = new AfterSaleQueryTools(new StubGateway() {}, disabled)
                .searchAfterSales("ORDER_CODE", "XJTS01", null, null,
                        context(new AtomicReference<>()));

        assertThat(text).isEqualTo("当前组织暂未开放售后查询能力。");
    }

    private AfterSaleToolAvailability availability() {
        var all = new AfterSaleToolAvailability.Capability(true, "ALL", Set.of());
        return new AfterSaleToolAvailability(all, all);
    }

    private ToolContext context(AtomicReference<ToolUiResult> published) {
        AgentToolRequestContext context = new AgentToolRequestContext(
                REQUEST_ID, IDENTITY, published::set, new ToolCallGuard(3));
        return new ToolContext(Map.of(AgentToolRequestContext.CONTEXT_KEY, context));
    }

    private abstract static class StubGateway implements AfterSaleQueryGateway {
        @Override
        public AfterSaleSearchResult search(
                AfterSaleIdentifierType type, String identifier,
                OffsetDateTime startTime, OffsetDateTime endTime,
                AgentIdentity identity, String requestId) {
            throw new AssertionError("search should not be called");
        }

        @Override
        public AfterSaleDetailResult detail(
                String afterSaleCode, AgentIdentity identity, String requestId) {
            throw new AssertionError("detail should not be called");
        }
    }
}
