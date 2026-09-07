package com.xjjk.agent.order.client;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** 兼容 order 服务历史大小写风格的内部响应包装。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderServiceResponse<T>(
        @JsonProperty("code") @JsonAlias("Code") Integer code,
        @JsonProperty("message") @JsonAlias({"Message", "msg", "Msg"}) String message,
        @JsonProperty("data") @JsonAlias("Data") T data) {
}
