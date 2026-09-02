package com.xjjk.agent.tenant.web;

import com.xjjk.agent.common.api.ApiErrorCode;
import com.xjjk.agent.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

@Component
public class TenantIdArgumentResolver
        implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        boolean hasAnnotation = parameter.hasParameterAnnotation(
                CurrentTenantId.class
        );

        Class<?> parameterType = parameter.getParameterType();
        boolean supportedType = parameterType == long.class
                || parameterType == Long.class;

        return hasAnnotation && supportedType;
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory
    ) {
        HttpServletRequest request = webRequest.getNativeRequest(
                HttpServletRequest.class
        );

        if (request == null) {
            throw new BusinessException(
                    ApiErrorCode.INTERNAL_SERVER_ERROR
            );
        }

        Object tenantId = request.getAttribute(
                TenantContext.TENANT_ID_ATTRIBUTE
        );

        if (!(tenantId instanceof Long)) {
            throw new BusinessException(
                    ApiErrorCode.INTERNAL_SERVER_ERROR
            );
        }

        return tenantId;
    }
}
