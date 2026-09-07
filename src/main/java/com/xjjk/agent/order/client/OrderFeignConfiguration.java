package com.xjjk.agent.order.client;

import feign.Retryer;
import org.springframework.context.annotation.Bean;

/** order 内部接口 Feign 配置；重试只允许由 Gateway 按瞬时故障规则执行。 */
public class OrderFeignConfiguration {

    @Bean
    public Retryer retryer() {
        return Retryer.NEVER_RETRY;
    }
}
