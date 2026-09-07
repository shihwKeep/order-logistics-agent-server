package com.xjjk.agent.product.client;

import com.xjjk.agent.product.domain.ProductSearchQuery;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CxProductSearchGatewayTest {

    @Test
    void sendsInternalTokenAndMapsTheCxEnvelope() {
        java.util.concurrent.atomic.AtomicReference<String> token = new java.util.concurrent.atomic.AtomicReference<>();
        CxProductClient client = (internalToken, request) -> {
            token.set(internalToken);
            return new CxProductResponse<>(1000, "成功", new CxProductSearchData(
                    List.of(new CxProductSearchItem(
                            12L, "SPU-12", "SKU-12", "鱼油", "60粒",
                            19900L, 8L, "ON_SHELF", "已上架", "image", false)),
                    1, 10, 1, false));
        };
        CxProductSearchGateway gateway = new CxProductSearchGateway(client, "internal-secret");

        var result = gateway.search(ProductSearchQuery.of("鱼油", 1, 10));

        assertThat(token.get()).isEqualTo("internal-secret");
        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.skuCode()).isEqualTo("SKU-12");
            assertThat(item.coverImageUrl()).isEqualTo("image");
        });
    }
}
