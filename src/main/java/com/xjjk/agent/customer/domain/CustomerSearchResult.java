package com.xjjk.agent.customer.domain;

import java.time.OffsetDateTime;
import java.util.List;

public record CustomerSearchResult(
        CustomerMatchType matchedBy,
        long total,
        boolean truncated,
        OffsetDateTime queriedAt,
        List<CustomerSearchItem> items) {
    public CustomerSearchResult {
        items = List.copyOf(items);
    }
}
