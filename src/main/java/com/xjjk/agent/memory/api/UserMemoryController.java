package com.xjjk.agent.memory.api;

import com.xjjk.agent.common.api.ApiResponse;
import com.xjjk.agent.identity.domain.AgentIdentity;
import com.xjjk.agent.identity.web.CurrentAgentIdentity;
import com.xjjk.agent.memory.api.dto.UpdateUserMemorySettingRequest;
import com.xjjk.agent.memory.api.dto.UpdateUserMemoryRequest;
import com.xjjk.agent.memory.api.dto.MemoryMutationResponse;
import com.xjjk.agent.memory.api.dto.UserMemoryPageResponse;
import com.xjjk.agent.memory.api.dto.UserMemorySettingResponse;
import com.xjjk.agent.memory.service.UserMemoryQueryService;
import com.xjjk.agent.memory.service.UserMemoryManagementService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
public class UserMemoryController {

    private final UserMemoryQueryService queryService;
    private final UserMemoryManagementService managementService;

    public UserMemoryController(UserMemoryQueryService queryService,
                                UserMemoryManagementService managementService) {
        this.queryService = queryService;
        this.managementService = managementService;
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

    @PutMapping("/memories/{memoryId}")
    public ApiResponse<MemoryMutationResponse> edit(
            @CurrentAgentIdentity AgentIdentity identity,
            @PathVariable String memoryId,
            @Valid @RequestBody UpdateUserMemoryRequest request
    ) {
        var result = managementService.edit(
                identity, memoryId, request.content(), request.retentionType());
        return ApiResponse.success(new MemoryMutationResponse(
                1, result.memoryId(), null));
    }

    @DeleteMapping("/memories/{memoryId}")
    public ApiResponse<MemoryMutationResponse> delete(
            @CurrentAgentIdentity AgentIdentity identity,
            @PathVariable String memoryId
    ) {
        var result = managementService.delete(identity, memoryId);
        return ApiResponse.success(new MemoryMutationResponse(
                result.affectedCount(), null, result.generation()));
    }

    @DeleteMapping(value = "/memories", params = "scope=explicit")
    public ApiResponse<MemoryMutationResponse> clearExplicit(
            @CurrentAgentIdentity AgentIdentity identity
    ) {
        var result = managementService.clearExplicit(identity);
        return ApiResponse.success(new MemoryMutationResponse(
                result.affectedCount(), null, result.generation()));
    }
}
