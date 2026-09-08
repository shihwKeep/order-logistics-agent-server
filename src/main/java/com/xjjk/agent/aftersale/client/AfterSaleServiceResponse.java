package com.xjjk.agent.aftersale.client;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** 兼容售后旧服务外层响应字段的大小写差异。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AfterSaleServiceResponse<T>(
        @JsonProperty("code") @JsonAlias("Code") Integer code,
        @JsonProperty("message") @JsonAlias({"Message", "msg", "Msg"}) String message,
        @JsonProperty("data") @JsonAlias("Data") T data) {
}
