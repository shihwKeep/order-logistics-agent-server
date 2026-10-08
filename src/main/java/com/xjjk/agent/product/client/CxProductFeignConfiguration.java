package com.xjjk.agent.product.client;

import feign.Client;
import feign.Retryer;
import feign.http2client.Http2Client;
import java.net.http.HttpClient.Version;
import org.springframework.context.annotation.Bean;

/** 商品只读查询的 Feign 配置；重试由上层稳定性策略统一管理。 */
public class CxProductFeignConfiguration {
    @Bean
    public Client client() {
        // cxservice is a local HTTP/1.1 endpoint in the desktop environment.
        // The JDK transport avoids the intermittent blocking observed with the
        // default Feign transport while preserving Feign's per-client timeouts.
        return new Http2Client(java.net.http.HttpClient.newBuilder()
                .version(Version.HTTP_1_1)
                .build());
    }

    @Bean
    public Retryer retryer() {
        return Retryer.NEVER_RETRY;
    }
}
