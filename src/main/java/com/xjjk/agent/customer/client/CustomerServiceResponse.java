package com.xjjk.agent.customer.client;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** 兼容客户服务现有大小写风格的响应信封。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CustomerServiceResponse<T>(
        @JsonProperty("code") @JsonAlias("Code") Integer code,
        @JsonProperty("message") @JsonAlias({"Message", "msg", "Msg"}) String message,
        @JsonProperty("data") @JsonAlias("Data") T data) {
}
