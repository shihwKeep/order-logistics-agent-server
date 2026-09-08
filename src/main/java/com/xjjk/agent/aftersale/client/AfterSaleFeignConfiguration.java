package com.xjjk.agent.aftersale.client;

import feign.Retryer;
import org.springframework.context.annotation.Bean;

/** 禁用 Feign 隐式重试，由网关按明确规则执行至多一次重试。 */
public class AfterSaleFeignConfiguration {
    @Bean
    Retryer retryer() {
        return Retryer.NEVER_RETRY;
    }
}
