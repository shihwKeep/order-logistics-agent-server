# 当前租户参数解析设计

## 目标

让 Controller 通过 `@CurrentTenantId long companyId` 获取已经校验过的租户 ID，避免重复读取、解析和校验 `X-Company-Id` 请求头。

## 组件职责

### `TenantInterceptor`

- 读取 `X-Company-Id` 请求头。
- 校验请求头是否缺失、格式是否合法、租户是否有权访问。
- 将通过校验的租户 ID 写入当前 `HttpServletRequest` 的 attribute。

### `@CurrentTenantId`

- 标记需要注入当前租户 ID 的 Controller 方法参数。
- 仅允许标注方法参数，运行时可被 Spring MVC 读取。

### `TenantIdArgumentResolver`

- 只处理带有 `@CurrentTenantId` 且类型为 `long` 或 `Long` 的参数。
- 从请求 attribute 读取租户 ID，不重新解析请求头。
- attribute 缺失时抛出服务端流程异常，表示租户拦截链没有正确执行。

### `TenantWebMvcConfiguration`

- 保留现有租户拦截器注册。
- 注册 `TenantIdArgumentResolver`。

## 请求数据流

1. 客户端发送 `X-Company-Id`。
2. `TenantInterceptor` 校验租户。
3. 拦截器把合法租户 ID 写入当前请求 attribute。
4. Spring MVC 调用 `TenantIdArgumentResolver`。
5. 参数解析器把租户 ID 注入带有 `@CurrentTenantId` 的 Controller 参数。

## 错误处理

- 缺少请求头：HTTP 400，`TENANT_HEADER_MISSING`。
- 请求头格式错误或非正数：HTTP 400，`TENANT_INVALID`。
- 请求租户与系统固定租户不同：HTTP 403，`TENANT_ACCESS_DENIED`。
- 参数解析阶段找不到已经校验的租户 ID：HTTP 500，表示服务端配置或调用链错误。

## 并发与 SSE

租户 ID 绑定在 `HttpServletRequest`，不写入静态变量或 `ThreadLocal`。这可以避免线程复用导致的租户数据泄漏，也不会假设 SSE 后续事件始终运行在最初的请求线程上。进入异步业务前，需要把 `companyId` 作为显式参数继续传递。

## 验证方式

本阶段使用 Postman 验证：

- 原有四种租户请求结果保持不变。
- 合法请求中，Controller 能收到租户 ID `1`。
- 未标注 `@CurrentTenantId` 的普通参数不受解析器影响。
- `/actuator/**` 不进入租户拦截链。

## 非目标

- 本阶段不实现登录用户身份认证。
- 不实现动态多租户切换。
- 不使用 `ThreadLocal` 保存租户上下文。
- 不修改数据库查询；后续数据访问层必须显式接收 `companyId`。
