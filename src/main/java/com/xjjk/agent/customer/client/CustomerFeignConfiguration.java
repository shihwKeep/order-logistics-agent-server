package com.xjjk.agent.customer.client;

import feign.Retryer;
import org.springframework.context.annotation.Bean;

/** Feign 禁止自动重试，所有重试均由 Gateway 的白名单规则控制。 */
public class CustomerFeignConfiguration {
    @Bean
    Retryer retryer() {
        return Retryer.NEVER_RETRY;
    }
}
