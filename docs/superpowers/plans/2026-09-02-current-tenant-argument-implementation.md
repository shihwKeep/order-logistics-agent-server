# Current Tenant Argument Resolver Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 Controller 使用 `@CurrentTenantId` 参数注解安全地获取已经过拦截器校验的租户 ID。

**Architecture:** `TenantInterceptor` 继续负责请求头解析与权限校验，并把合法租户 ID 写入当前请求 attribute。`TenantIdArgumentResolver` 只负责识别 `@CurrentTenantId` 参数并从 request attribute 注入值，不重复校验，也不使用 `ThreadLocal`。

**Tech Stack:** Java 21、Spring Boot 3.5、Spring MVC `HandlerMethodArgumentResolver`、Postman

---

## 文件结构

- 新建 `src/main/java/com/xjjk/agent/tenant/web/CurrentTenantId.java`：声明 Controller 参数注解。
- 新建 `src/main/java/com/xjjk/agent/tenant/web/TenantIdArgumentResolver.java`：解析注解参数。
- 修改 `src/main/java/com/xjjk/agent/tenant/config/TenantWebMvcConfiguration.java`：注册参数解析器。
- 修改 `src/main/java/com/xjjk/agent/system/api/dto/PingResponse.java`：在验证响应中增加 `companyId`。
- 修改 `src/main/java/com/xjjk/agent/system/api/SystemController.java`：使用 `@CurrentTenantId`。

本阶段按已确认的学习方式使用 Postman 验证，不新增测试类。

### Task 1：声明当前租户参数注解

**Files:**
- Create: `src/main/java/com/xjjk/agent/tenant/web/CurrentTenantId.java`

- [ ] **Step 1：创建注解**

```java
package com.xjjk.agent.tenant.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentTenantId {
}
```

- [ ] **Step 2：在 IDEA 中确认无编译错误**

预期：文件没有红色错误提示，`CurrentTenantId` 可以被其他 Java 类导入。

### Task 2：实现 Spring MVC 参数解析器

**Files:**
- Create: `src/main/java/com/xjjk/agent/tenant/web/TenantIdArgumentResolver.java`

- [ ] **Step 1：创建参数解析器**

```java
package com.xjjk.agent.tenant.web;

import com.xjjk.agent.common.api.ApiErrorCode;
import com.xjjk.agent.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

@Component
public class TenantIdArgumentResolver
        implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        boolean hasAnnotation = parameter.hasParameterAnnotation(
                CurrentTenantId.class
        );

        Class<?> parameterType = parameter.getParameterType();
        boolean supportedType = parameterType == long.class
                || parameterType == Long.class;

        return hasAnnotation && supportedType;
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
            throw new BusinessException(
                    ApiErrorCode.INTERNAL_SERVER_ERROR
            );
        }

        Object tenantId = request.getAttribute(
                TenantInterceptor.TENANT_ID_ATTRIBUTE
        );

        if (!(tenantId instanceof Long)) {
            throw new BusinessException(
                    ApiErrorCode.INTERNAL_SERVER_ERROR
            );
        }

        return tenantId;
    }
}
```

- [ ] **Step 2：理解解析器的边界**

`supportsParameter` 只有在参数同时满足“带 `@CurrentTenantId`”和“类型为 `long` 或 `Long`”时才返回 `true`。`resolveArgument` 只读取拦截器写入的值；缺失时返回统一的服务器错误，不重新读取请求头。

### Task 3：注册参数解析器

**Files:**
- Modify: `src/main/java/com/xjjk/agent/tenant/config/TenantWebMvcConfiguration.java`

- [ ] **Step 1：用下面的完整内容更新配置类**

```java
package com.xjjk.agent.tenant.config;

import com.xjjk.agent.tenant.web.TenantIdArgumentResolver;
import com.xjjk.agent.tenant.web.TenantInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class TenantWebMvcConfiguration implements WebMvcConfigurer {

    private final TenantInterceptor tenantInterceptor;
    private final TenantIdArgumentResolver tenantIdArgumentResolver;

    public TenantWebMvcConfiguration(
            TenantInterceptor tenantInterceptor,
            TenantIdArgumentResolver tenantIdArgumentResolver
    ) {
        this.tenantInterceptor = tenantInterceptor;
        this.tenantIdArgumentResolver = tenantIdArgumentResolver;
    }

    @Override
    public void addInterceptors(
            InterceptorRegistry registry
    ) {
        registry.addInterceptor(tenantInterceptor)
                .addPathPatterns("/api/v1/**");
    }

    @Override
    public void addArgumentResolvers(
            List<HandlerMethodArgumentResolver> resolvers
    ) {
        resolvers.add(tenantIdArgumentResolver);
    }
}
```

- [ ] **Step 2：重新启动应用**

在 IDEA 中停止旧进程并运行 `OrderLogisticsAgentServerApplication`。

预期：控制台出现应用启动完成日志，没有 Bean 循环依赖或参数解析器注册错误。

### Task 4：让 Controller 使用当前租户 ID

**Files:**
- Modify: `src/main/java/com/xjjk/agent/system/api/dto/PingResponse.java`
- Modify: `src/main/java/com/xjjk/agent/system/api/SystemController.java`

- [ ] **Step 1：更新 `PingResponse`**

```java
package com.xjjk.agent.system.api.dto;

public record PingResponse(
        String status,
        String application,
        long companyId
) {
}
```

- [ ] **Step 2：更新 `SystemController`**

```java
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
```

- [ ] **Step 3：重新启动应用**

预期：应用正常启动，Controller 方法参数可以被 Spring MVC 解析。

### Task 5：使用 Postman 回归验证

**Files:**
- No file changes.

- [ ] **Step 1：验证合法租户**

发送：

```text
GET http://localhost:8080/api/v1/system/ping
X-Company-Id: 1
```

预期 HTTP 200，响应 `data` 中包含：

```json
{
  "status": "UP",
  "application": "order-logistics-agent-server",
  "companyId": 1
}
```

- [ ] **Step 2：回归验证缺少请求头**

发送同一请求但删除 `X-Company-Id`。

预期 HTTP 400，业务码为 `TENANT_HEADER_MISSING`。

- [ ] **Step 3：回归验证非法格式**

发送 `X-Company-Id: abc`。

预期 HTTP 400，业务码为 `TENANT_INVALID`。

- [ ] **Step 4：回归验证租户越权**

发送 `X-Company-Id: 2`。

预期 HTTP 403，业务码为 `TENANT_ACCESS_DENIED`。

- [ ] **Step 5：验证 Actuator 不受影响**

发送：

```text
GET http://localhost:8080/actuator/health
```

不添加租户请求头。预期 HTTP 200，响应状态为 `UP`。

### Task 6：提交当前后端检查点

**Files:**
- Stage the Nacos、统一异常、租户拦截与当前租户参数解析相关文件。

- [ ] **Step 1：查看待提交文件**

```powershell
git status --short
```

预期：只看到本阶段已确认的后端配置、公共异常、租户组件以及系统接口修改。

- [ ] **Step 2：提交检查点**

```powershell
git add pom.xml src/main/java src/main/resources/application.properties
git commit -m "feat: integrate Nacos config and tenant context"
```

预期：Git 创建一个新的本地提交。设计和计划文档已有各自独立提交，不会被重复提交。

- [ ] **Step 3：确认工作区状态**

```powershell
git status --short
```

预期：没有输出，表示工作区干净。
