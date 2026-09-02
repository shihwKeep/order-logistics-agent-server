package com.xjjk.agent.auth.service;

import com.xjjk.agent.auth.api.dto.AuthSessionResponse;
import com.xjjk.agent.auth.api.dto.AuthenticatedUserResponse;
import com.xjjk.agent.auth.client.SspxOAuthClient;
import com.xjjk.agent.auth.client.SspxOAuthClientException;
import com.xjjk.agent.auth.client.dto.SspxTokenResponse;
import com.xjjk.agent.common.api.ApiErrorCode;
import com.xjjk.agent.common.exception.BusinessException;
import com.xjjk.agent.identity.domain.AgentIdentity;
import com.xjjk.agent.identity.service.SspxAuthenticationService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Set;

@Service
public class AuthApplicationService {

    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<Integer> INVALID_CREDENTIAL_CODES =
            Set.of(10000, 10002);
    private static final Set<Integer> UNAVAILABLE_ACCOUNT_CODES =
            Set.of(10001, 10003, 10004, 10005, 10006, 10007, 10010);

    private final SspxOAuthClient oauthClient;
    private final SspxAuthenticationService authenticationService;

    public AuthApplicationService(
            SspxOAuthClient oauthClient,
            SspxAuthenticationService authenticationService
    ) {
        this.oauthClient = oauthClient;
        this.authenticationService = authenticationService;
    }

    public AuthSessionResponse login(String username, String password) {
        SspxTokenResponse token = invoke(
                () -> oauthClient.passwordGrant(username, password)
        );
        validateLoginToken(token);
        return buildSession(token);
    }

    public AuthSessionResponse refresh(String refreshToken) {
        SspxTokenResponse token = invoke(
                () -> oauthClient.refreshGrant(refreshToken)
        );
        if (!hasAccessToken(token)) {
            throw new BusinessException(ApiErrorCode.AUTH_TOKEN_INVALID);
        }
        return buildSession(token);
    }

    private SspxTokenResponse invoke(TokenSupplier supplier) {
        try {
            return supplier.get();
        } catch (SspxOAuthClientException exception) {
            ApiErrorCode errorCode = exception.reason()
                    == SspxOAuthClientException.Reason.UNAVAILABLE
                    ? ApiErrorCode.AUTH_SERVICE_UNAVAILABLE
                    : ApiErrorCode.AUTH_RESPONSE_INVALID;
            throw new BusinessException(errorCode);
        }
    }

    private void validateLoginToken(SspxTokenResponse token) {
        if (hasAccessToken(token)) {
            return;
        }
        if (token != null && INVALID_CREDENTIAL_CODES.contains(token.code())) {
            throw new BusinessException(ApiErrorCode.AUTH_CREDENTIALS_INVALID);
        }
        if (token != null && UNAVAILABLE_ACCOUNT_CODES.contains(token.code())) {
            throw new BusinessException(ApiErrorCode.AUTH_ACCOUNT_UNAVAILABLE);
        }
        throw new BusinessException(ApiErrorCode.AUTH_RESPONSE_INVALID);
    }

    private boolean hasAccessToken(SspxTokenResponse token) {
        return token != null
                && token.accessToken() != null
                && !token.accessToken().isBlank();
    }

    private AuthSessionResponse buildSession(SspxTokenResponse token) {
        String authorization = "Bearer " + token.accessToken();
        AgentIdentity identity = authenticationService.authenticate(authorization);
        AuthenticatedUserResponse user = new AuthenticatedUserResponse(
                identity.userId(),
                identity.account(),
                identity.name(),
                identity.orgId(),
                identity.tenantId()
        );
        return new AuthSessionResponse(
                "Bearer",
                token.accessToken(),
                token.refreshToken(),
                resolveExpiry(token),
                user
        );
    }

    private OffsetDateTime resolveExpiry(SspxTokenResponse token) {
        if (token.expiresTime() != null && token.expiresTime() > 0) {
            return Instant.ofEpochMilli(token.expiresTime())
                    .atZone(CHINA_ZONE)
                    .toOffsetDateTime();
        }
        long expiresIn = token.expiresIn() == null ? 0L : token.expiresIn();
        return OffsetDateTime.now(CHINA_ZONE).plusSeconds(expiresIn);
    }

    @FunctionalInterface
    private interface TokenSupplier {
        SspxTokenResponse get();
    }
}
