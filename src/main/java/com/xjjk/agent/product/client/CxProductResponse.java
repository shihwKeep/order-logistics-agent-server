package com.xjjk.agent.product.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

/** cxservice 通用响应包装。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CxProductResponse<T>(
        @JsonProperty("code") @JsonAlias("Code") Integer code,
        @JsonProperty("msg") @JsonAlias("Msg") String message,
        @JsonProperty("data") @JsonAlias("Data") T data) {
}
