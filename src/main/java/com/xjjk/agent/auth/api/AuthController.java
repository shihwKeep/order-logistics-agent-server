package com.xjjk.agent.auth.api;

import com.xjjk.agent.auth.api.dto.AuthSessionResponse;
import com.xjjk.agent.auth.api.dto.LoginRequest;
import com.xjjk.agent.auth.api.dto.RefreshRequest;
import com.xjjk.agent.auth.service.AuthApplicationService;
import com.xjjk.agent.common.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthApplicationService authApplicationService;

    public AuthController(AuthApplicationService authApplicationService) {
        this.authApplicationService = authApplicationService;
    }

    @PostMapping("/login")
    public ApiResponse<AuthSessionResponse> login(
            @Valid @RequestBody LoginRequest request
    ) {
        return ApiResponse.success(authApplicationService.login(
                request.username(),
                request.password()
        ));
    }

    @PostMapping("/refresh")
    public ApiResponse<AuthSessionResponse> refresh(
            @Valid @RequestBody RefreshRequest request
    ) {
        return ApiResponse.success(authApplicationService.refresh(
                request.refreshToken()
        ));
    }
}
