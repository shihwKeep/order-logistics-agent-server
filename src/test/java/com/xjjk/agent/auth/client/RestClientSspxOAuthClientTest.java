package com.xjjk.agent.auth.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xjjk.agent.auth.client.dto.SspxTokenResponse;
import com.xjjk.agent.auth.config.SspxOAuthProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RestClientSspxOAuthClientTest {

    @Test
    void sendsPasswordGrantAsFormBody() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://localhost:9092/oauth2/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_FORM_URLENCODED
                ))
                .andExpect(content().string(allOf(
                        containsString("grant_type=password"),
                        containsString("client_id=test-client"),
                        containsString("client_secret=test-secret"),
                        containsString("scope=profile"),
                        containsString("name=agent"),
                        containsString("pwd=password")
                )))
                .andRespond(withSuccess("""
                        {
                          "access_token":"access",
                          "refresh_token":"refresh",
                          "token_type":"Bearer",
                          "expires_in":3600,
                          "expires_time":1788364800000
                        }
                        """, MediaType.APPLICATION_JSON));
        SspxOAuthProperties properties = new SspxOAuthProperties(
                "http://localhost:9092",
                new SspxOAuthProperties.OAuth("test-client", "test-secret")
        );
        RestClientSspxOAuthClient client = new RestClientSspxOAuthClient(
                builder,
                properties,
                new ObjectMapper()
        );

        SspxTokenResponse response = client.passwordGrant("agent", "password");

        assertThat(response.accessToken()).isEqualTo("access");
        assertThat(response.refreshToken()).isEqualTo("refresh");
        server.verify();
    }
}
