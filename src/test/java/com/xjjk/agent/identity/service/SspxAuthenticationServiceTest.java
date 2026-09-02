package com.xjjk.agent.identity.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xjjk.agent.identity.client.SspxAuthClient;
import com.xjjk.agent.identity.client.dto.SspxResponse;
import com.xjjk.agent.identity.domain.AgentIdentity;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class SspxAuthenticationServiceTest {

    @Test
    void mapsSspxCompanyIdToAgentTenantId() {
        String payload = """
                {"Id":5186,"Account":"agent","Name":"坐席","OrgId":1061,"CompanyId":7}
                """;
        String encoded = HexFormat.of().formatHex(
                payload.getBytes(StandardCharsets.UTF_8)
        );
        SspxAuthClient client = authorization ->
                new SspxResponse<>(1000, "success", encoded);
        SspxAuthenticationService service = new SspxAuthenticationService(
                client,
                new ObjectMapper()
        );

        AgentIdentity identity = service.authenticate("Bearer token");

        assertThat(identity.tenantId()).isEqualTo(7L);
    }
}
