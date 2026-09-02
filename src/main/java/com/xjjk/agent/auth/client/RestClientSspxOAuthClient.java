package com.xjjk.agent.auth.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xjjk.agent.auth.client.dto.SspxTokenResponse;
import com.xjjk.agent.auth.config.SspxOAuthProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;

@Component
public class RestClientSspxOAuthClient implements SspxOAuthClient {

    private final RestClient restClient;
    private final SspxOAuthProperties.OAuth oauth;
    private final ObjectMapper objectMapper;

    public RestClientSspxOAuthClient(
            RestClient.Builder builder,
            SspxOAuthProperties properties,
            ObjectMapper objectMapper
    ) {
        this.restClient = builder.baseUrl(properties.baseUrl()).build();
        this.oauth = properties.oauth();
        this.objectMapper = objectMapper;
    }

    @Override
    public SspxTokenResponse passwordGrant(String username, String password) {
        MultiValueMap<String, String> form = commonForm("password");
        form.add("username", username);
        form.add("password", password);
        return requestToken(form);
    }

    @Override
    public SspxTokenResponse refreshGrant(String refreshToken) {
        MultiValueMap<String, String> form = commonForm("refresh_token");
        form.add("refresh_token", refreshToken);
        return requestToken(form);
    }

    private MultiValueMap<String, String> commonForm(String grantType) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", grantType);
        form.add("client_id", oauth.clientId());
        form.add("client_secret", oauth.clientSecret());
        form.add("scope", "profile");
        return form;
    }

    private SspxTokenResponse requestToken(MultiValueMap<String, String> form) {
        try {
            SspxTokenResponse response = restClient.post()
                    .uri("/oauth2/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .exchange((request, httpResponse) -> {
                        try {
                            return objectMapper.readValue(
                                    httpResponse.getBody(),
                                    SspxTokenResponse.class
                            );
                        } catch (IOException exception) {
                            throw new SspxOAuthClientException(
                                    SspxOAuthClientException.Reason.INVALID_RESPONSE,
                                    exception
                            );
                        }
                    });
            if (response == null) {
                throw new SspxOAuthClientException(
                        SspxOAuthClientException.Reason.INVALID_RESPONSE,
                        null
                );
            }
            return response;
        } catch (SspxOAuthClientException exception) {
            throw exception;
        } catch (ResourceAccessException exception) {
            throw new SspxOAuthClientException(
                    SspxOAuthClientException.Reason.UNAVAILABLE,
                    exception
            );
        } catch (RestClientException exception) {
            throw new SspxOAuthClientException(
                    SspxOAuthClientException.Reason.INVALID_RESPONSE,
                    exception
            );
        }
    }
}
