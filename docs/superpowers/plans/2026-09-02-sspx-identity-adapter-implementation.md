# SSPX Identity Adapter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 Agent 使用原始 Bearer Token 调用现有 `sspx-server` 重新认证，并向业务接口提供可信且经过租户交叉校验的当前用户身份。

**Architecture:** OpenFeign 负责调用既有 `/authorizationcenter/user/current`；身份服务负责检查 SSPX 业务码、十六进制解码和 JSON 转换；Spring MVC 拦截器在租户拦截器之后完成认证与租户比对；参数解析器通过 `@CurrentAgentIdentity` 向 Controller 注入可信身份。`sspx-server` 与 Gateway 只读、不修改。

**Tech Stack:** Java 21、Spring Boot 3.5、Spring Cloud OpenFeign、Jackson、Spring MVC、Nacos、Postman

---

## 执行约束

- 所有改动直接提交到用户已授权的 `main` 分支。
- 不修改 `D:\GitCode\sspx-server` 和 `D:\GitCode\gateway` 中的任何文件。
- 不记录或提交 Bearer Token。
- 按用户已确认的开发方式，本阶段不新增测试类；以 IDEA 编译、应用启动和 Postman 场景验收为准。
- 不新增数据库表或 Flyway 脚本。

### Task 1: 引入 OpenFeign 并启用客户端扫描

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/java/com/xjjk/agent/OrderLogisticsAgentServerApplication.java`

- [ ] **Step 1: 在 `pom.xml` dependencies 中增加 OpenFeign starter**

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-openfeign</artifactId>
</dependency>
```

- [ ] **Step 2: 在启动类启用 Feign**

增加导入和注解：

```java
import org.springframework.cloud.openfeign.EnableFeignClients;

@EnableFeignClients
```

- [ ] **Step 3: 在 IDEA 中重新加载 Maven**

预期：`org.springframework.cloud.openfeign` 和 `feign` 类型可正常解析，项目无红色依赖错误。

- [ ] **Step 4: 提交依赖改动**

```powershell
git add pom.xml src/main/java/com/xjjk/agent/OrderLogisticsAgentServerApplication.java
git commit -m "build: add OpenFeign for SSPX authentication"
```

### Task 2: 增加认证错误码和外部接口契约

**Files:**
- Modify: `src/main/java/com/xjjk/agent/common/api/ApiErrorCode.java`
- Create: `src/main/java/com/xjjk/agent/identity/client/dto/SspxResponse.java`
- Create: `src/main/java/com/xjjk/agent/identity/client/dto/SspxCurrentUserPayload.java`
- Create: `src/main/java/com/xjjk/agent/identity/client/SspxAuthClient.java`
- Create: `src/main/java/com/xjjk/agent/identity/client/SspxFeignConfiguration.java`

- [ ] **Step 1: 在 `ApiErrorCode` 的内部错误之前加入认证错误码**

```java
AUTH_HEADER_MISSING(
        HttpStatus.UNAUTHORIZED,
        "AUTH_HEADER_MISSING",
        "缺少登录凭证"
),

AUTH_TOKEN_INVALID(
        HttpStatus.UNAUTHORIZED,
        "AUTH_TOKEN_INVALID",
        "登录状态无效或已过期"
),

AUTH_SERVICE_UNAVAILABLE(
        HttpStatus.SERVICE_UNAVAILABLE,
        "AUTH_SERVICE_UNAVAILABLE",
        "认证服务暂时不可用"
),

AUTH_RESPONSE_INVALID(
        HttpStatus.BAD_GATEWAY,
        "AUTH_RESPONSE_INVALID",
        "认证服务响应异常"
),
```

- [ ] **Step 2: 创建 SSPX 外层响应 DTO**

```java
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
```

- [ ] **Step 3: 创建 SSPX 用户载荷 DTO**

```java
package com.xjjk.agent.identity.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SspxCurrentUserPayload(
        @JsonProperty("Id") Long id,
        @JsonProperty("Account") String account,
        @JsonProperty("Name") String name,
        @JsonProperty("OrgId") Long orgId,
        @JsonProperty("CompanyId") Long companyId
) {
}
```

- [ ] **Step 4: 创建只对本客户端生效的禁重试配置**

```java
package com.xjjk.agent.identity.client;

import feign.Retryer;
import org.springframework.context.annotation.Bean;

public class SspxFeignConfiguration {

    @Bean
    public Retryer retryer() {
        return Retryer.NEVER_RETRY;
    }
}
```

- [ ] **Step 5: 创建 Feign 客户端**

```java
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
```

- [ ] **Step 6: IDEA 编译检查**

预期：新增 DTO、Feign client、错误码均可编译。

- [ ] **Step 7: 提交外部契约**

```powershell
git add src/main/java/com/xjjk/agent/common/api/ApiErrorCode.java src/main/java/com/xjjk/agent/identity/client
git commit -m "feat: define SSPX authentication contract"
```

### Task 3: 实现可信身份和 SSPX 响应解析

**Files:**
- Create: `src/main/java/com/xjjk/agent/identity/domain/AgentIdentity.java`
- Create: `src/main/java/com/xjjk/agent/identity/service/SspxAuthenticationService.java`

- [ ] **Step 1: 创建不可变的 Agent 身份对象**

```java
package com.xjjk.agent.identity.domain;

public record AgentIdentity(
        long userId,
        String account,
        String name,
        long orgId,
        long companyId
) {
}
```

- [ ] **Step 2: 创建认证服务**

```java
package com.xjjk.agent.identity.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xjjk.agent.common.api.ApiErrorCode;
import com.xjjk.agent.common.exception.BusinessException;
import com.xjjk.agent.identity.client.SspxAuthClient;
import com.xjjk.agent.identity.client.dto.SspxCurrentUserPayload;
import com.xjjk.agent.identity.client.dto.SspxResponse;
import com.xjjk.agent.identity.domain.AgentIdentity;
import feign.FeignException;
import feign.RetryableException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

@Service
public class SspxAuthenticationService {

    private static final int SSPX_SUCCESS_CODE = 1000;

    private final SspxAuthClient sspxAuthClient;
    private final ObjectMapper objectMapper;

    public SspxAuthenticationService(
            SspxAuthClient sspxAuthClient,
            ObjectMapper objectMapper
    ) {
        this.sspxAuthClient = sspxAuthClient;
        this.objectMapper = objectMapper;
    }

    public AgentIdentity authenticate(String authorization) {
        SspxResponse<String> response = requestCurrentUser(authorization);

        if (response == null
                || response.code() == null
                || response.code() != SSPX_SUCCESS_CODE
                || response.data() == null
                || response.data().isBlank()) {
            throw new BusinessException(ApiErrorCode.AUTH_TOKEN_INVALID);
        }

        SspxCurrentUserPayload payload = decodePayload(response.data());
        validatePayload(payload);

        return new AgentIdentity(
                payload.id(),
                payload.account(),
                payload.name(),
                payload.orgId(),
                payload.companyId()
        );
    }

    private SspxResponse<String> requestCurrentUser(String authorization) {
        try {
            return sspxAuthClient.getCurrentUser(authorization);
        } catch (RetryableException exception) {
            throw new BusinessException(ApiErrorCode.AUTH_SERVICE_UNAVAILABLE);
        } catch (FeignException exception) {
            if (exception.status() >= 400 && exception.status() < 500) {
                throw new BusinessException(ApiErrorCode.AUTH_TOKEN_INVALID);
            }
            throw new BusinessException(ApiErrorCode.AUTH_SERVICE_UNAVAILABLE);
        }
    }

    private SspxCurrentUserPayload decodePayload(String encodedPayload) {
        try {
            byte[] jsonBytes = HexFormat.of().parseHex(encodedPayload);
            String json = new String(jsonBytes, StandardCharsets.UTF_8);
            return objectMapper.readValue(json, SspxCurrentUserPayload.class);
        } catch (IllegalArgumentException | JsonProcessingException exception) {
            throw new BusinessException(ApiErrorCode.AUTH_RESPONSE_INVALID);
        }
    }

    private void validatePayload(SspxCurrentUserPayload payload) {
        if (payload == null
                || payload.id() == null
                || payload.id() <= 0
                || payload.account() == null
                || payload.account().isBlank()
                || payload.orgId() == null
                || payload.orgId() <= 0
                || payload.companyId() == null
                || payload.companyId() <= 0) {
            throw new BusinessException(ApiErrorCode.AUTH_RESPONSE_INVALID);
        }
    }
}
```

- [ ] **Step 3: IDEA 编译检查**

预期：身份服务可编译，不依赖 SSPX 或 Gateway 的公共 JAR。

- [ ] **Step 4: 提交身份解析**

```powershell
git add src/main/java/com/xjjk/agent/identity/domain src/main/java/com/xjjk/agent/identity/service
git commit -m "feat: decode trusted SSPX identity"
```

### Task 4: 建立认证拦截器和 Controller 参数注入

**Files:**
- Create: `src/main/java/com/xjjk/agent/identity/web/SspxAuthenticationInterceptor.java`
- Create: `src/main/java/com/xjjk/agent/identity/web/CurrentAgentIdentity.java`
- Create: `src/main/java/com/xjjk/agent/identity/web/AgentIdentityArgumentResolver.java`
- Create: `src/main/java/com/xjjk/agent/identity/config/IdentityWebMvcConfiguration.java`
- Modify: `src/main/java/com/xjjk/agent/tenant/config/TenantWebMvcConfiguration.java`

- [ ] **Step 1: 创建认证拦截器**

```java
package com.xjjk.agent.identity.web;

import com.xjjk.agent.common.api.ApiErrorCode;
import com.xjjk.agent.common.exception.BusinessException;
import com.xjjk.agent.identity.domain.AgentIdentity;
import com.xjjk.agent.identity.service.SspxAuthenticationService;
import com.xjjk.agent.tenant.web.TenantInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class SspxAuthenticationInterceptor implements HandlerInterceptor {

    public static final String IDENTITY_ATTRIBUTE =
            SspxAuthenticationInterceptor.class.getName() + ".identity";

    private static final String BEARER_PREFIX = "Bearer ";

    private final SspxAuthenticationService authenticationService;

    public SspxAuthenticationInterceptor(
            SspxAuthenticationService authenticationService
    ) {
        this.authenticationService = authenticationService;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler
    ) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);

        if (authorization == null
                || !authorization.startsWith(BEARER_PREFIX)
                || authorization.substring(BEARER_PREFIX.length()).isBlank()) {
            throw new BusinessException(ApiErrorCode.AUTH_HEADER_MISSING);
        }

        AgentIdentity identity = authenticationService.authenticate(authorization);
        Object tenantAttribute = request.getAttribute(TenantInterceptor.TENANT_ID_ATTRIBUTE);

        if (!(tenantAttribute instanceof Long tenantId)) {
            throw new BusinessException(ApiErrorCode.INTERNAL_SERVER_ERROR);
        }

        if (identity.companyId() != tenantId) {
            throw new BusinessException(ApiErrorCode.TENANT_ACCESS_DENIED);
        }

        request.setAttribute(IDENTITY_ATTRIBUTE, identity);
        return true;
    }
}
```

- [ ] **Step 2: 创建参数注解**

```java
package com.xjjk.agent.identity.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentAgentIdentity {
}
```

- [ ] **Step 3: 创建参数解析器**

```java
package com.xjjk.agent.identity.web;

import com.xjjk.agent.common.api.ApiErrorCode;
import com.xjjk.agent.common.exception.BusinessException;
import com.xjjk.agent.identity.domain.AgentIdentity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

@Component
public class AgentIdentityArgumentResolver
        implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentAgentIdentity.class)
                && parameter.getParameterType() == AgentIdentity.class;
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory
    ) {
        HttpServletRequest request = webRequest.getNativeRequest(
                HttpServletRequest.class
        );

        if (request == null) {
            throw new BusinessException(ApiErrorCode.INTERNAL_SERVER_ERROR);
        }

        Object identity = request.getAttribute(
                SspxAuthenticationInterceptor.IDENTITY_ATTRIBUTE
        );

        if (!(identity instanceof AgentIdentity agentIdentity)) {
            throw new BusinessException(ApiErrorCode.INTERNAL_SERVER_ERROR);
        }

        return agentIdentity;
    }
}
```

- [ ] **Step 4: 明确租户拦截器优先级**

在 `TenantWebMvcConfiguration` 增加 `Ordered` 导入，并在注册末尾设置顺序：

```java
import org.springframework.core.Ordered;

registry.addInterceptor(tenantInterceptor)
        .addPathPatterns("/api/v1/**")
        .order(Ordered.HIGHEST_PRECEDENCE);
```

- [ ] **Step 5: 注册身份拦截器和参数解析器**

```java
package com.xjjk.agent.identity.config;

import com.xjjk.agent.identity.web.AgentIdentityArgumentResolver;
import com.xjjk.agent.identity.web.SspxAuthenticationInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class IdentityWebMvcConfiguration implements WebMvcConfigurer {

    private final SspxAuthenticationInterceptor authenticationInterceptor;
    private final AgentIdentityArgumentResolver identityArgumentResolver;

    public IdentityWebMvcConfiguration(
            SspxAuthenticationInterceptor authenticationInterceptor,
            AgentIdentityArgumentResolver identityArgumentResolver
    ) {
        this.authenticationInterceptor = authenticationInterceptor;
        this.identityArgumentResolver = identityArgumentResolver;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authenticationInterceptor)
                .addPathPatterns("/api/v1/**")
                .order(Ordered.HIGHEST_PRECEDENCE + 1);
    }

    @Override
    public void addArgumentResolvers(
            List<HandlerMethodArgumentResolver> resolvers
    ) {
        resolvers.add(identityArgumentResolver);
    }
}
```

- [ ] **Step 6: IDEA 编译检查并提交**

```powershell
git add src/main/java/com/xjjk/agent/identity/web src/main/java/com/xjjk/agent/identity/config src/main/java/com/xjjk/agent/tenant/config/TenantWebMvcConfiguration.java
git commit -m "feat: protect Agent APIs with SSPX identity"
```

### Task 5: 增加 whoami 验证接口

**Files:**
- Create: `src/main/java/com/xjjk/agent/system/api/dto/CurrentIdentityResponse.java`
- Modify: `src/main/java/com/xjjk/agent/system/api/SystemController.java`

- [ ] **Step 1: 创建返回 DTO**

```java
package com.xjjk.agent.system.api.dto;

public record CurrentIdentityResponse(
        long userId,
        String account,
        String name,
        long orgId,
        long companyId
) {
}
```

- [ ] **Step 2: 在 `SystemController` 增加 whoami**

新增导入：

```java
import com.xjjk.agent.identity.domain.AgentIdentity;
import com.xjjk.agent.identity.web.CurrentAgentIdentity;
import com.xjjk.agent.system.api.dto.CurrentIdentityResponse;
```

新增方法：

```java
@GetMapping("/whoami")
public ApiResponse<CurrentIdentityResponse> whoami(
        @CurrentAgentIdentity AgentIdentity identity
) {
    CurrentIdentityResponse response = new CurrentIdentityResponse(
            identity.userId(),
            identity.account(),
            identity.name(),
            identity.orgId(),
            identity.companyId()
    );

    return ApiResponse.success(response);
}
```

- [ ] **Step 3: IDEA 编译检查并提交**

```powershell
git add src/main/java/com/xjjk/agent/system/api
git commit -m "feat: expose authenticated identity endpoint"
```

### Task 6: 增加 Nacos 配置并完成端到端验收

**Files:**
- External configuration: Nacos `order-logistics-agent-server.properties`, group `ORDER_LOGISTICS_AGENT`, namespace `order-logistics-agent-dev`

- [ ] **Step 1: 在 Nacos 增加 SSPX 配置**

```properties
integration.sspx.base-url=http://127.0.0.1:9092

spring.cloud.openfeign.client.config.sspx-auth.connect-timeout=1000
spring.cloud.openfeign.client.config.sspx-auth.read-timeout=2000
spring.cloud.openfeign.client.config.sspx-auth.logger-level=basic
```

- [ ] **Step 2: 启动 `sspx-server` 和 Agent**

预期：Agent 启动成功，没有 Feign URL 占位符或 Bean 注入错误。

- [ ] **Step 3: 验证健康检查不需要认证**

```http
GET http://127.0.0.1:8080/actuator/health
```

预期：HTTP 200，`status=UP`。

- [ ] **Step 4: 验证缺少认证头**

```http
GET http://127.0.0.1:8080/api/v1/system/whoami
X-Company-Id: 1
```

预期：HTTP 401，`code=AUTH_HEADER_MISSING`。

- [ ] **Step 5: 验证无效 Token**

在 Postman 本地填写一个无效 Bearer Token，不在聊天或 Git 中保存。

预期：HTTP 401，`code=AUTH_TOKEN_INVALID`。

- [ ] **Step 6: 验证有效 Token**

在 Postman 本地填写现有系统产生的有效 Bearer Token：

```http
GET http://127.0.0.1:8080/api/v1/system/whoami
X-Company-Id: 1
Authorization: Bearer <仅在本机填写>
```

预期：HTTP 200，`code=SUCCESS`，返回真实 `userId`、`orgId`、`companyId=1`。

- [ ] **Step 7: 验证认证服务不可用**

停止 `sspx-server` 后保留 Bearer Token 再请求。

预期：HTTP 503，`code=AUTH_SERVICE_UNAVAILABLE`，并在约 2 秒超时范围内返回。

- [ ] **Step 8: 检查日志和仓库边界**

```powershell
git -C D:\GitCode\sspx-server status --short
git -C D:\GitCode\gateway status --short
git status --short
```

预期：前两个生产项目没有由本次实现产生的改动；Agent 日志未输出 Token、十六进制 Data 或完整 CurrentUser。

- [ ] **Step 9: 最终提交状态确认**

```powershell
git log -6 --oneline
git status --short
```

预期：Agent 工作区干净，认证功能的分步提交均位于 `main`。
