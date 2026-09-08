package com.xjjk.agent.customer.domain;

public record CustomerSearchItem(
        long customerId,
        String customerCode,
        String displayName,
        String gradeName,
        String assetTypeName,
        String customerTypeName) {
}
