package com.xjjk.agent.identity.client;

import feign.Retryer;
import org.springframework.context.annotation.Bean;

public class SspxFeignConfiguration {

    @Bean
    public Retryer retryer() {
        return Retryer.NEVER_RETRY;
    }
}
