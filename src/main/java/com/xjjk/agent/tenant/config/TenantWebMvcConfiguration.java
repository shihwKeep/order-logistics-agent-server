package com.xjjk.agent.tenant.config;

import com.xjjk.agent.tenant.web.TenantIdArgumentResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class TenantWebMvcConfiguration implements WebMvcConfigurer {

    private final TenantIdArgumentResolver tenantIdArgumentResolver;

    public TenantWebMvcConfiguration(
            TenantIdArgumentResolver tenantIdArgumentResolver
    ) {
        this.tenantIdArgumentResolver = tenantIdArgumentResolver;
    }

    @Override
    public void addArgumentResolvers(
            List<HandlerMethodArgumentResolver> resolvers
    ) {
        resolvers.add(tenantIdArgumentResolver);
    }
}
