package com.xjjk.agent.product.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

/** cxservice Agent 商品只读接口。 */
@FeignClient(
        name = "cx-product",
        url = "${integration.cx.base-url}",
        configuration = CxProductFeignConfiguration.class)
public interface CxProductClient {

    @PostMapping("/internal/agent/products/search")
    CxProductResponse<CxProductSearchData> search(
            @RequestHeader("X-Agent-Internal-Token") String internalToken,
            @RequestBody CxProductSearchRequest request);
}
