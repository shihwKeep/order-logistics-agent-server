package com.xjjk.agent.chat.api.dto;

import com.xjjk.agent.product.domain.ProductSearchResult;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatStreamProductResultContractTest {

    @Test
    void wrapsProductListInGenericResultEnvelope() {
        ProductSearchResult products = new ProductSearchResult(
                "鱼油", 1, 10, 0, false, List.of());

        ChatStreamPayloads.Result result =
                new ChatStreamPayloads.Result("product-list", products);

        assertThat(result.kind()).isEqualTo("product-list");
        assertThat(result.data()).isSameAs(products);
    }
}
