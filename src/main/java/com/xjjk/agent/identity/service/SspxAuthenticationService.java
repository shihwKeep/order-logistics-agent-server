package com.xjjk.agent.identity.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xjjk.agent.common.api.ApiErrorCode;
import com.xjjk.agent.common.exception.BusinessException;
import com.xjjk.agent.identity.client.SspxAuthClient;
import com.xjjk.agent.identity.client.dto.SspxCurrentUserPayload;
import com.xjjk.agent.identity.client.dto.SspxResponse;
import com.xjjk.agent.identity.domain.AgentIdentity;
import feign.FeignException;
import feign.RetryableException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

@Service
public class SspxAuthenticationService {

    private static final int SSPX_SUCCESS_CODE = 1000;

    private final SspxAuthClient sspxAuthClient;
    private final ObjectMapper objectMapper;

    public SspxAuthenticationService(
            SspxAuthClient sspxAuthClient,
            ObjectMapper objectMapper
    ) {
        this.sspxAuthClient = sspxAuthClient;
        this.objectMapper = objectMapper;
    }

    public AgentIdentity authenticate(String authorization) {
        SspxResponse<String> response = requestCurrentUser(authorization);

        if (response == null
                || response.code() == null
                || response.code() != SSPX_SUCCESS_CODE
                || response.data() == null
                || response.data().isBlank()) {
            throw new BusinessException(ApiErrorCode.AUTH_TOKEN_INVALID);
        }

        SspxCurrentUserPayload payload = decodePayload(response.data());
        validatePayload(payload);

        return new AgentIdentity(
                payload.id(),
                payload.account(),
                payload.name(),
                payload.orgId(),
                payload.companyId()
        );
    }

    private SspxResponse<String> requestCurrentUser(String authorization) {
        try {
            return sspxAuthClient.getCurrentUser(authorization);
        } catch (RetryableException exception) {
            throw new BusinessException(ApiErrorCode.AUTH_SERVICE_UNAVAILABLE);
        } catch (FeignException exception) {
            if (exception.status() >= 400 && exception.status() < 500) {
                throw new BusinessException(ApiErrorCode.AUTH_TOKEN_INVALID);
            }
            throw new BusinessException(ApiErrorCode.AUTH_SERVICE_UNAVAILABLE);
        }
    }

    private SspxCurrentUserPayload decodePayload(String encodedPayload) {
        try {
            byte[] jsonBytes = HexFormat.of().parseHex(encodedPayload);
            String json = new String(jsonBytes, StandardCharsets.UTF_8);
            return objectMapper.readValue(json, SspxCurrentUserPayload.class);
        } catch (IllegalArgumentException | JsonProcessingException exception) {
            throw new BusinessException(ApiErrorCode.AUTH_RESPONSE_INVALID);
        }
    }

    private void validatePayload(SspxCurrentUserPayload payload) {
        if (payload == null
                || payload.id() == null
                || payload.id() <= 0
                || payload.account() == null
                || payload.account().isBlank()
                || payload.orgId() == null
                || payload.orgId() <= 0
                || payload.companyId() == null
                || payload.companyId() <= 0) {
            throw new BusinessException(ApiErrorCode.AUTH_RESPONSE_INVALID);
        }
    }
}
