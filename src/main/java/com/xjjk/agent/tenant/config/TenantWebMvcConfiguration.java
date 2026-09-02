package com.xjjk.agent.tenant.config;

import com.xjjk.agent.tenant.web.TenantIdArgumentResolver;
import com.xjjk.agent.tenant.web.TenantInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class TenantWebMvcConfiguration implements WebMvcConfigurer {

    private final TenantInterceptor tenantInterceptor;
    private final TenantIdArgumentResolver tenantIdArgumentResolver;

    public TenantWebMvcConfiguration(
            TenantInterceptor tenantInterceptor,
            TenantIdArgumentResolver tenantIdArgumentResolver
    ) {
        this.tenantInterceptor = tenantInterceptor;
        this.tenantIdArgumentResolver = tenantIdArgumentResolver;
    }

    @Override
    public void addInterceptors(
            InterceptorRegistry registry
    ) {
        registry.addInterceptor(tenantInterceptor)
                .addPathPatterns("/api/v1/**")
                .order(Ordered.HIGHEST_PRECEDENCE);
    }

    @Override
    public void addArgumentResolvers(
            List<HandlerMethodArgumentResolver> resolvers
    ) {
        resolvers.add(tenantIdArgumentResolver);
    }
}
