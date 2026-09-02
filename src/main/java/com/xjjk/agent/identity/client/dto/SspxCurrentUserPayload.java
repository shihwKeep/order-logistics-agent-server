package com.xjjk.agent.identity.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SspxCurrentUserPayload(
        @JsonProperty("Id") Long id,
        @JsonProperty("Account") String account,
        @JsonProperty("Name") String name,
        @JsonProperty("OrgId") Long orgId,
        @JsonProperty("CompanyId") Long companyId
) {
}
