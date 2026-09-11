package com.xjjk.agent.memory.api;

import com.xjjk.agent.common.api.ApiResponse;
import com.xjjk.agent.identity.domain.AgentIdentity;
import com.xjjk.agent.identity.web.CurrentAgentIdentity;
import com.xjjk.agent.memory.api.dto.UpdateUserMemorySettingRequest;
import com.xjjk.agent.memory.api.dto.UserMemoryPageResponse;
import com.xjjk.agent.memory.api.dto.UserMemorySettingResponse;
import com.xjjk.agent.memory.service.UserMemoryQueryService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
public class UserMemoryController {

    private final UserMemoryQueryService queryService;

    public UserMemoryController(UserMemoryQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/memories")
    public ApiResponse<UserMemoryPageResponse> list(
            @CurrentAgentIdentity AgentIdentity identity,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "10") int pageSize
    ) {
        return ApiResponse.success(queryService.list(identity, cursor, pageSize));
    }

    @GetMapping("/memory-settings")
    public ApiResponse<UserMemorySettingResponse> getSetting(
            @CurrentAgentIdentity AgentIdentity identity
    ) {
        return ApiResponse.success(queryService.getSetting(identity));
    }

    @PutMapping("/memory-settings")
    public ApiResponse<UserMemorySettingResponse> updateSetting(
            @CurrentAgentIdentity AgentIdentity identity,
            @Valid @RequestBody UpdateUserMemorySettingRequest request
    ) {
        return ApiResponse.success(queryService.updateSetting(
                identity, request.autoExtractEnabled()));
    }
}
