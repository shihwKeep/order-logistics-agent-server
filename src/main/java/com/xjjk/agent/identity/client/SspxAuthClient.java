package com.xjjk.agent.identity.client;

import com.xjjk.agent.identity.client.dto.SspxResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;

@FeignClient(
        name = "sspx-auth",
        url = "${integration.sspx.base-url}",
        configuration = SspxFeignConfiguration.class
)
public interface SspxAuthClient {

    @GetMapping("/authorizationcenter/user/current")
    SspxResponse<String> getCurrentUser(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization
    );
}
