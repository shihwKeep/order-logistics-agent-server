package com.xjjk.agent.identity.config;

import com.xjjk.agent.identity.web.AgentIdentityArgumentResolver;
import com.xjjk.agent.identity.web.SspxAuthenticationInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class IdentityWebMvcConfiguration implements WebMvcConfigurer {

    private final SspxAuthenticationInterceptor authenticationInterceptor;
    private final AgentIdentityArgumentResolver identityArgumentResolver;

    public IdentityWebMvcConfiguration(
            SspxAuthenticationInterceptor authenticationInterceptor,
            AgentIdentityArgumentResolver identityArgumentResolver
    ) {
        this.authenticationInterceptor = authenticationInterceptor;
        this.identityArgumentResolver = identityArgumentResolver;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authenticationInterceptor)
                .addPathPatterns("/api/v1/**")
                .order(Ordered.HIGHEST_PRECEDENCE + 1);
    }

    @Override
    public void addArgumentResolvers(
            List<HandlerMethodArgumentResolver> resolvers
    ) {
        resolvers.add(identityArgumentResolver);
    }
}
