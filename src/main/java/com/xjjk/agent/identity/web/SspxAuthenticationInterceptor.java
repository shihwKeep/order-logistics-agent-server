package com.xjjk.agent.identity.web;

import com.xjjk.agent.common.api.ApiErrorCode;
import com.xjjk.agent.common.exception.BusinessException;
import com.xjjk.agent.identity.domain.AgentIdentity;
import com.xjjk.agent.identity.service.SspxAuthenticationService;
import com.xjjk.agent.tenant.web.TenantInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class SspxAuthenticationInterceptor implements HandlerInterceptor {

    public static final String IDENTITY_ATTRIBUTE =
            SspxAuthenticationInterceptor.class.getName() + ".identity";

    private static final String BEARER_PREFIX = "Bearer ";

    private final SspxAuthenticationService authenticationService;

    public SspxAuthenticationInterceptor(
            @Lazy SspxAuthenticationService authenticationService
    ) {
        this.authenticationService = authenticationService;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler
    ) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);

        if (authorization == null
                || !authorization.startsWith(BEARER_PREFIX)
                || authorization.substring(BEARER_PREFIX.length()).isBlank()) {
            throw new BusinessException(ApiErrorCode.AUTH_HEADER_MISSING);
        }

        AgentIdentity identity = authenticationService.authenticate(authorization);
        Object tenantAttribute = request.getAttribute(
                TenantInterceptor.TENANT_ID_ATTRIBUTE
        );

        if (!(tenantAttribute instanceof Long tenantId)) {
            throw new BusinessException(ApiErrorCode.INTERNAL_SERVER_ERROR);
        }

        if (identity.companyId() != tenantId) {
            throw new BusinessException(ApiErrorCode.TENANT_ACCESS_DENIED);
        }

        request.setAttribute(IDENTITY_ATTRIBUTE, identity);
        return true;
    }
}
