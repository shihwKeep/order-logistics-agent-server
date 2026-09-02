package com.xjjk.agent.auth.service;

import com.xjjk.agent.auth.api.dto.AuthSessionResponse;
import com.xjjk.agent.auth.client.SspxOAuthClient;
import com.xjjk.agent.auth.client.dto.SspxTokenResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xjjk.agent.common.api.ApiErrorCode;
import com.xjjk.agent.common.exception.BusinessException;
import com.xjjk.agent.identity.service.SspxAuthenticationService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

class AuthApplicationServiceTest {

    @Test
    void logsInAndReturnsTenantIdentity() {
        SspxOAuthClient oauthClient = new StubOAuthClient(
                new SspxTokenResponse(
                        "access",
                        "refresh",
                        "Bearer",
                        3600L,
                        1788364800000L,
                        null,
                        null
                )
        );
        String payload = """
                {"Id":5186,"Account":"agent","Name":"坐席","OrgId":1061,"CompanyId":7}
                """;
        String encoded = HexFormat.of().formatHex(
                payload.getBytes(StandardCharsets.UTF_8)
        );
        SspxAuthenticationService authenticationService =
                new SspxAuthenticationService(
                        authorization -> {
                            assertThat(authorization).isEqualTo("Bearer access");
                            return new com.xjjk.agent.identity.client.dto.SspxResponse<>(
                                    1000,
                                    "success",
                                    encoded
                            );
                        },
                        new ObjectMapper()
                );
        AuthApplicationService service = new AuthApplicationService(
                oauthClient,
                authenticationService
        );

        AuthSessionResponse result = service.login("agent", "password");

        assertThat(result.accessToken()).isEqualTo("access");
        assertThat(result.user().tenantId()).isEqualTo(7L);
    }

    @Test
    void hidesWhetherAccountOrPasswordWasWrong() {
        SspxOAuthClient oauthClient = new StubOAuthClient(
                new SspxTokenResponse(
                        null, null, null, null, null, 10002, "密码错误"
                )
        );
        AuthApplicationService service = new AuthApplicationService(
                oauthClient,
                new SspxAuthenticationService(
                        authorization -> null,
                        new ObjectMapper()
                )
        );

        assertThatThrownBy(() -> service.login("agent", "wrong"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(
                                ApiErrorCode.AUTH_CREDENTIALS_INVALID
                        )
                );
    }

    private record StubOAuthClient(SspxTokenResponse response)
            implements SspxOAuthClient {

        @Override
        public SspxTokenResponse passwordGrant(
                String username,
                String password
        ) {
            return response;
        }

        @Override
        public SspxTokenResponse refreshGrant(String refreshToken) {
            return response;
        }
    }
}
