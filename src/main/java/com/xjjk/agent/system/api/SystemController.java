package com.xjjk.agent.system.api;

import com.xjjk.agent.common.api.ApiResponse;
import com.xjjk.agent.system.api.dto.PingResponse;
import com.xjjk.agent.tenant.web.CurrentTenantId;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
public class SystemController {

    @GetMapping("/ping")
    public ApiResponse<PingResponse> ping(
            @CurrentTenantId long companyId
    ) {
        PingResponse response = new PingResponse(
                "UP",
                "order-logistics-agent-server",
                companyId
        );

        return ApiResponse.success(response);
    }
}
