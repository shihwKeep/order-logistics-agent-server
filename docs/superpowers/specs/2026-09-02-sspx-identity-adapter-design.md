# SSPX 身份适配器设计

## 1. 背景与目标

`order-logistics-agent-server`（下称 Agent）位于现有 Gateway 之后。桌面端发送原始 `Authorization: Bearer <token>` 和 `X-Company-Id`，Gateway 将请求转发给 Agent。

Gateway 当前能够解析身份并添加 `CurrentUser` 请求头，但该请求头本身没有签名。Agent 的 `8080` 端口若被直接访问，调用方可以伪造这个请求头。因此，Agent 不把客户端或 Gateway 传入的 `CurrentUser` 当作可信身份，而是使用原始 Bearer Token 调用现有 `sspx-server` 重新验证。

本阶段目标：

- 复用 `sspx-server` 中已有的 Sa-Token 登录状态，不在 Agent 建立第二套账号、密码或 Token 体系。
- 为 Agent 建立可信的当前用户上下文。
- 将认证用户的 `CompanyId` 与请求中的 `X-Company-Id` 交叉校验。
- 使用 Postman 覆盖成功、未登录、服务异常等主要场景。
- 保持对现有 `sspx-server` 和 Gateway Java 代码零修改。

## 2. 范围

### 2.1 本阶段包含

- Agent 引入 Spring Cloud OpenFeign。
- Agent 调用 `GET /authorizationcenter/user/current` 验证原始 Bearer Token。
- 解析 SSPX 的 `Code`、`Msg`、`Data` 响应。
- 将十六进制 `Data` 解码为可信用户对象。
- 建立 `AgentIdentity`、`@CurrentAgentIdentity` 和参数解析器。
- 保护全部 `/api/v1/**` 接口，包括现有 `ping`。
- 新增 `GET /api/v1/system/whoami`，供当前阶段验证可信身份。
- 为认证错误增加统一业务错误码和 HTTP 状态映射。

### 2.2 本阶段不包含

- 不修改 `sspx-server` 的认证接口、Sa-Token 配置或 Redis 结构。
- 不修改 Gateway Java 代码，也不在本阶段配置生产网络隔离。
- 不信任或解析传入的 `CurrentUser` 请求头。
- 不在 Agent 缓存认证结果。
- 不在 Agent 建立用户表、密码字段或 BCrypt 登录逻辑。
- 不查询用户的数据权限范围；订单权限服务在后续独立阶段接入。
- 不新增 Flyway 迁移或业务数据库表。
- 不改造前端真实登录；当前先用 Postman 和既有 Token 验证。

## 3. 已有接口契约

Agent 调用：

```http
GET http://127.0.0.1:9092/authorizationcenter/user/current
Authorization: Bearer <token>
```

`sspx-server` 的 HTTP 状态通常为 `200`，业务结果通过响应体判断。响应字段首字母大写：

```json
{
  "Code": 1000,
  "Msg": "success",
  "Data": "十六进制编码的 CurrentUser JSON"
}
```

成功码固定为 `1000`。无 Token 或 Token 无效的实际响应示例为 HTTP 200、业务码 `4003`、`Data` 为 `null`。因此 Agent 不得只检查 HTTP 状态。

`Data` 解码后的用户字段为：

```text
Id
Account
Name
OrgId
CompanyId
CheckSecretTokenFlag
```

Agent 当前只把 `Id`、`Account`、`Name`、`OrgId`、`CompanyId` 纳入身份上下文。`CheckSecretTokenFlag` 不参与本阶段业务判断。

## 4. 方案选择

### 4.1 采用方案：OpenFeign 调用 SSPX

Agent 使用 OpenFeign 调用既有认证接口。该方式与现有 Gateway 的调用方式一致，客户端边界清晰，超时配置集中，后续替换专用 Token introspection 接口时只需替换适配器内部实现。

### 4.2 未采用方案

- **直接读取 SSPX Redis**：会让 Agent 耦合 `sspx-server` 的 Redis key、序列化和 Token 存储细节，不采用。
- **信任 Gateway 的 `CurrentUser` 请求头**：Agent 端口可被绕过时可遭伪造，不采用。
- **在 Agent 再实现 Sa-Token 登录系统**：产生两套认证源和账号数据，不采用。

## 5. 组件与职责

建议包结构如下：

```text
com.xjjk.agent.identity
├── api
│   └── dto
│       └── CurrentIdentityResponse
├── client
│   ├── SspxAuthClient
│   └── dto
│       ├── SspxCurrentUserPayload
│       └── SspxResponse
├── config
│   └── IdentityWebMvcConfiguration
├── domain
│   └── AgentIdentity
├── service
│   └── SspxAuthenticationService
└── web
    ├── CurrentAgentIdentity
    ├── AgentIdentityArgumentResolver
    └── SspxAuthenticationInterceptor
```

各组件职责：

- `SspxAuthClient`：只负责把原始 `Authorization` 请求头转发到 SSPX。
- `SspxResponse<T>`：显式映射大写的 `Code`、`Msg`、`Data`，避免依赖默认命名推断。
- `SspxCurrentUserPayload`：显式映射解码 JSON 中首字母大写的字段。
- `SspxAuthenticationService`：检查业务码和 Data，完成十六进制解码、UTF-8 JSON 反序列化、必要字段校验，生成 `AgentIdentity`。
- `AgentIdentity`：Agent 内部可信且不可变的用户身份，包含 `userId`、`account`、`name`、`orgId`、`companyId`。
- `SspxAuthenticationInterceptor`：读取 Authorization，调用认证服务，对比可信身份的 `companyId` 和租户拦截器已确认的租户 ID，并把身份写入当前 `HttpServletRequest` 属性。
- `@CurrentAgentIdentity` 与 `AgentIdentityArgumentResolver`：让 Controller 通过方法参数取得可信身份，避免业务代码自行读取请求头或 request attribute。
- `IdentityWebMvcConfiguration`：注册身份拦截器和参数解析器，并明确拦截器顺序。

Agent 不依赖 `com.xjjk:common` 来复用 DTO。适配层自行声明最小外部契约，以免两个应用因公共 JAR 版本形成强耦合。十六进制解码使用 Java 21 `HexFormat`，字符集固定为 UTF-8，JSON 使用 Spring Boot 已提供的 Jackson。

## 6. 请求流程与拦截顺序

```text
桌面端 / Postman
  │ Authorization + X-Company-Id
  ▼
Gateway（真实链路）
  │ 保留原始 Authorization
  ▼
TenantInterceptor（顺序 1）
  │ 校验请求租户并写入可信租户 request attribute
  ▼
SspxAuthenticationInterceptor（顺序 2）
  │ 使用原始 Bearer Token 调用 sspx-server
  │ 解析可信 AgentIdentity
  │ 校验 AgentIdentity.companyId == 当前可信租户 ID
  │ 写入身份 request attribute
  ▼
Controller
  │ @CurrentTenantId + @CurrentAgentIdentity
  ▼
业务服务
```

租户拦截器必须先执行，身份拦截器使用其已经验证的租户 request attribute，不重新解析 `X-Company-Id`。当前固定租户配置仍为 `1`，但校验链路不省略，为后续多租户扩展保留安全边界。

`/api/v1/**` 全部经过两个拦截器，现有 `/api/v1/system/ping` 也需要登录。`/actuator/**` 不进入这两个拦截器，保证健康检查不依赖认证服务。

## 7. 错误处理

统一通过现有 `BusinessException`、`GlobalExceptionHandler` 和 `ApiResponse` 返回错误：

| 场景 | HTTP 状态 | 业务错误码 | 处理方式 |
|---|---:|---|---|
| 缺少 Authorization 或不是 Bearer 格式 | 401 | `AUTH_HEADER_MISSING` | 不调用 SSPX |
| SSPX `Code != 1000` 或 Data 为空 | 401 | `AUTH_TOKEN_INVALID` | 不向客户端透传 SSPX 内部消息 |
| SSPX 连接或读取超时、连接失败 | 503 | `AUTH_SERVICE_UNAVAILABLE` | 失败关闭，不允许请求继续 |
| SSPX 成功响应无法十六进制解码、无法解析 JSON或缺少必要身份字段 | 502 | `AUTH_RESPONSE_INVALID` | 视为上游响应契约异常 |
| 认证用户 `CompanyId` 与当前租户不一致 | 403 | `TENANT_ACCESS_DENIED` | 拒绝跨租户请求 |

Feign 的非 2xx HTTP 响应也属于上游调用异常：认证明确拒绝类响应映射为 `AUTH_TOKEN_INVALID`；无法归类的服务端或协议异常映射为 `AUTH_SERVICE_UNAVAILABLE` 或 `AUTH_RESPONSE_INVALID`。实现计划中应通过一个适配层集中完成映射，Controller 不处理 Feign 异常。

错误响应继续使用项目现有结构：

```json
{
  "code": "AUTH_TOKEN_INVALID",
  "message": "登录状态无效或已过期",
  "data": null,
  "timestamp": "..."
}
```

## 8. 超时、重试与可用性策略

Nacos 增加以下配置：

```properties
integration.sspx.base-url=http://127.0.0.1:9092

spring.cloud.openfeign.client.config.sspx-auth.connect-timeout=1000
spring.cloud.openfeign.client.config.sspx-auth.read-timeout=2000
spring.cloud.openfeign.client.config.sspx-auth.logger-level=basic
```

- Feign client 名称为 `sspx-auth`。
- 连接超时 1 秒，读取超时 2 秒。
- 明确禁用自动重试，避免一次业务请求重复访问认证服务并放大故障。
- 本阶段每个受保护请求都重新校验，不缓存认证结果，确保退出登录或踢下线尽快生效。
- SSPX 不可用时采用 fail closed：返回 503，不降级为信任请求头或放行。

本地开发使用固定地址。后续接入生产服务发现时，可将 `base-url` 替换为环境配置或 Nacos 服务名，不改变业务层接口。

## 9. 安全与日志要求

- 禁止记录完整 `Authorization`、原始 Token、十六进制 Data 或解码后的完整 CurrentUser JSON。
- Feign 日志级别使用 `BASIC`，不得启用包含请求头和响应体的 `FULL`。
- 错误日志可记录 requestId、上游服务名、异常类别和耗时，但不能记录凭证。
- `CurrentUser` 请求头无论是否存在都不参与认证决定。
- `AgentIdentity` 只能由成功验证 SSPX 响应的认证服务创建并写入请求上下文。
- 不向客户端暴露 SSPX 的内部错误消息、Redis key 或异常堆栈。
- 当前本机允许 Postman 直连 Agent 用于学习；生产部署仍必须通过防火墙、容器网络或安全组限制 Agent 端口只能由 Gateway 访问。Token 重新校验是纵深防御，不能替代网络隔离。

## 10. 验证接口

新增：

```http
GET /api/v1/system/whoami
X-Company-Id: 1
Authorization: Bearer <existing-token>
```

成功时返回经过 SSPX 验证并转换后的最小身份信息：

```json
{
  "code": "SUCCESS",
  "message": "success",
  "data": {
    "userId": 10001,
    "account": "masked-example",
    "name": "example",
    "orgId": 1,
    "companyId": 1
  },
  "timestamp": "..."
}
```

示例值只用于描述返回结构，不作为代码中的固定数据。`whoami` 不返回 Token、权限列表或 SSPX 原始响应。现有 `ping` 保持原本职责，不添加用户字段。

## 11. Postman 验收标准

1. 不带 `Authorization` 访问 `/api/v1/system/whoami`：HTTP 401，`AUTH_HEADER_MISSING`。
2. 使用无效或过期 Token：HTTP 401，`AUTH_TOKEN_INVALID`。
3. 使用现有有效 Token 且 `X-Company-Id: 1`：HTTP 200，`code=SUCCESS`，返回身份的 `companyId=1`。
4. 携带任意 Token 但缺少或错误填写 `X-Company-Id`：仍由租户拦截器优先返回既有租户错误。
5. 使用有效 Token 但认证身份 CompanyId 与请求租户不一致：HTTP 403，`TENANT_ACCESS_DENIED`。当前若没有其他租户 Token，可在后续关键自动化测试中覆盖，不能通过伪造 `CurrentUser` 验证。
6. 停止 `sspx-server` 后携带 Bearer Token 请求：HTTP 503，`AUTH_SERVICE_UNAVAILABLE`，约在配置超时范围内结束。
7. 不带认证信息访问 `/actuator/health`：HTTP 200，状态为 `UP`。
8. 检查 Agent 日志：不出现完整 Token、Data 或完整 CurrentUser JSON。

用户只在本机 Postman 中填写既有 Bearer Token，不将 Token 发到聊天、Git、配置文件或截图中。

## 12. 后续演进

本阶段验收后再分别推进：

1. 配置 Gateway 到 Agent 的真实路由并验证 Authorization 原样传递。
2. 将前端内存演示登录替换为真实登录流程。
3. 对接现有权限服务，取得订单数据范围，并在 EC 只读查询前强制应用。
4. 视生产要求为 `sspx-server` 增加专用 Token introspection DTO、服务间认证或网关签名头。
5. 为认证调用增加 Micrometer 指标和 Tempo 链路观测，包括成功率、失败类型和耗时，但不采集 Token。

这些后续事项不属于本设计的实现范围。
