package com.xjjk.agent.aftersale.domain;

import java.time.OffsetDateTime;
import java.util.List;

/** 售后搜索领域结果，与下游 Feign DTO 隔离。 */
public record AfterSaleSearchResult(
        AfterSaleIdentifierType matchedBy,
        long total,
        boolean truncated,
        OffsetDateTime queriedAt,
        List<Item> items) {

    public record Item(
            String afterSaleCode,
            Integer statusCode,
            String statusText,
            OffsetDateTime createdAt,
            boolean finished,
            OffsetDateTime finishedAt,
            String customerDisplayName,
            String customerCode,
            String orderCode,
            String returnLogisticsCode,
            String assigneeDisplayName) {
    }
}
