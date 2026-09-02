package com.xjjk.agent.identity.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SspxResponse<T>(
        @JsonProperty("Code") Integer code,
        @JsonProperty("Msg") String message,
        @JsonProperty("Data") T data
) {
}
