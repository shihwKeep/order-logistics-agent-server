package com.xjjk.agent.identity.web;

import com.xjjk.agent.common.api.ApiErrorCode;
import com.xjjk.agent.common.exception.BusinessException;
import com.xjjk.agent.identity.domain.AgentIdentity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

@Component
public class AgentIdentityArgumentResolver
        implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentAgentIdentity.class)
                && parameter.getParameterType() == AgentIdentity.class;
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
            throw new BusinessException(ApiErrorCode.INTERNAL_SERVER_ERROR);
        }

        Object identity = request.getAttribute(
                SspxAuthenticationInterceptor.IDENTITY_ATTRIBUTE
        );

        if (!(identity instanceof AgentIdentity agentIdentity)) {
            throw new BusinessException(ApiErrorCode.INTERNAL_SERVER_ERROR);
        }

        return agentIdentity;
    }
}
