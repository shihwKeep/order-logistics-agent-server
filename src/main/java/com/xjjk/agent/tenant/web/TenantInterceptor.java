package com.xjjk.agent.tenant.web;

import com.xjjk.agent.common.api.ApiErrorCode;
import com.xjjk.agent.common.exception.BusinessException;
import com.xjjk.agent.tenant.config.TenantProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class TenantInterceptor implements HandlerInterceptor {

    public static final String COMPANY_ID_HEADER = "X-Company-Id";
    public static final String TENANT_ID_ATTRIBUTE =
            TenantInterceptor.class.getName() + ".tenantId";

    private final TenantProperties tenantProperties;

    public TenantInterceptor(TenantProperties tenantProperties) {
        this.tenantProperties = tenantProperties;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler
    ) {
        String headerValue = request.getHeader(COMPANY_ID_HEADER);

        if (headerValue == null || headerValue.isBlank()) {
            throw new BusinessException(
                    ApiErrorCode.TENANT_HEADER_MISSING
            );
        }

        long companyId;

        try {
            companyId = Long.parseLong(headerValue.trim());
        } catch (NumberFormatException exception) {
            throw new BusinessException(
                    ApiErrorCode.TENANT_INVALID
            );
        }

        if (companyId <= 0) {
            throw new BusinessException(
                    ApiErrorCode.TENANT_INVALID
            );
        }

        if (companyId != tenantProperties.fixedId()) {
            throw new BusinessException(
                    ApiErrorCode.TENANT_ACCESS_DENIED
            );
        }

        request.setAttribute(TENANT_ID_ATTRIBUTE, companyId);
        return true;
    }
}