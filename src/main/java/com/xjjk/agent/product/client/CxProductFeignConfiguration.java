package com.xjjk.agent.product.client;

import feign.Retryer;
import org.springframework.context.annotation.Bean;

/** 商品只读查询的 Feign 配置；重试由上层稳定性策略统一管理。 */
public class CxProductFeignConfiguration {
    @Bean
    public Retryer retryer() {
        return Retryer.NEVER_RETRY;
    }
}
