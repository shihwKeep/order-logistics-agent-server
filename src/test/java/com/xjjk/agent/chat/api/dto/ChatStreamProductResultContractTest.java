package com.xjjk.agent.chat.api.dto;

import com.xjjk.agent.product.domain.ProductSearchResult;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatStreamProductResultContractTest {

    @Test
    void wrapsProductListInGenericResultEnvelope() {
        ProductSearchResult products = new ProductSearchResult(
                "鱼油", 1, 10, 0, false, List.of());

        ChatStreamPayloads.Result result =
                new ChatStreamPayloads.Result(
                        "product-list", 1,
                        OffsetDateTime.parse("2026-09-07T10:15:30+08:00"),
                        products);

        assertThat(result.kind()).isEqualTo("product-list");
        assertThat(result.schemaVersion()).isEqualTo(1);
        assertThat(result.queriedAt()).isNotNull();
        assertThat(result.data()).isSameAs(products);
    }
}
