# 桌面端账号密码登录设计

## 1. 背景与目标

`order-logistics-agent-web` 当前只有内存中的演示身份，不能使用真实坐席账号登录。现有 `sspx-server` 已提供 OAuth2 密码授权与用户身份查询能力，现有 Gateway 通过共享 Redis 识别 SSPX 签发的 Bearer Token。

本次实现真实账号密码登录，并保持以下边界：

- 不修改 `sspx-server` 和 `gateway` 的源码或配置文件。
- 登录相关前后端代码由助手实现。
- 登录名支持 SSPX 已有的账号、手机号或邮箱。
- 前端不提交租户 ID；租户只能从认证后的 SSPX 身份获得。
- Token 和 OAuth 客户端密钥不得进入 Git、日志或 Vue 渲染进程的持久化存储。

本次不实现验证码、忘记密码、短信登录、企微登录、多租户手工切换和服务端完整单点注销改造。

## 2. 方案选择

采用 Agent 登录代理：Electron 主进程调用 Agent 匿名登录接口，Agent 使用服务端 OAuth 客户端配置调用 SSPX。这样可以满足内嵌账号密码登录，同时避免把 `client_secret` 打包到桌面程序。

不采用以下方案：

- Electron 直接调用 SSPX：桌面应用无法安全保管 `client_secret`。
- OAuth2 授权码与 PKCE：更适合正式公共客户端，但会跳转 SSPX 登录页，不符合本阶段内嵌账号密码登录要求。

由于现有 Gateway 会拦截所有不携带 Token 的请求，本地登录和刷新接口由 Electron 主进程直连 Agent `8080`。登录后的受保护业务请求继续统一经过 Gateway `8081`。

## 3. 登录数据流

```text
Vue 登录窗口
  └─ username + password
       ↓ IPC（密码不落盘）
Electron 主进程
       ↓ POST http://127.0.0.1:8080/api/v1/auth/login
Agent 登录代理
       ↓ POST http://127.0.0.1:9092/oauth2/token
SSPX OAuth2 密码授权
       ↓ access_token + refresh_token + 到期信息
Agent
       ↓ GET /authorizationcenter/user/current
SSPX 身份信息
       ↓ companyId 在边界处映射为 tenantId
Electron 主进程
       ↓ safeStorage 加密保存会话
后续业务请求 → Gateway :8081/agent/** → Agent :8080
```

Agent 在返回登录成功前必须使用新 access token 查询一次 SSPX 当前用户。Token 签发成功但身份查询失败时，整个登录仍视为失败，不创建本地会话。

## 4. Agent API 合同

### 4.1 登录

```http
POST /api/v1/auth/login
Content-Type: application/json
```

```json
{
  "username": "账号、手机号或邮箱",
  "password": "用户密码"
}
```

成功响应：

```json
{
  "code": "SUCCESS",
  "message": "success",
  "data": {
    "tokenType": "Bearer",
    "accessToken": "<access-token>",
    "refreshToken": "<refresh-token>",
    "expiresAt": "2026-09-03T02:00:00+08:00",
    "user": {
      "userId": 123,
      "account": "user-account",
      "name": "display-name",
      "orgId": 456,
      "tenantId": 1
    }
  },
  "timestamp": "2026-09-02 23:30:00"
}
```

### 4.2 刷新

```http
POST /api/v1/auth/refresh
Content-Type: application/json
```

```json
{
  "refreshToken": "<refresh-token>"
}
```

刷新成功后 Agent 同样使用新 access token重新查询当前用户，再返回与登录一致的会话结构。Agent 只允许一次刷新尝试，不自动循环重试。

### 4.3 错误合同

| HTTP 状态 | 业务码 | 场景 |
| --- | --- | --- |
| 400 | `VALIDATION_FAILED` | 登录名、密码或 refresh token 为空 |
| 401 | `AUTH_CREDENTIALS_INVALID` | 账号不存在或密码错误 |
| 401 | `AUTH_TOKEN_INVALID` | refresh token 无效或身份校验失败 |
| 403 | `AUTH_ACCOUNT_UNAVAILABLE` | 账号锁定、停用、离职或不允许登录 |
| 502 | `AUTH_RESPONSE_INVALID` | SSPX 返回结构不完整或无法解析 |
| 503 | `AUTH_SERVICE_UNAVAILABLE` | SSPX 连接失败或超时 |

账号不存在和密码错误使用同一对外提示“账号或密码错误”，避免账号枚举。账号状态异常可以返回不包含敏感细节的可操作提示。Agent 不重试密码登录，避免一次错误提交被 SSPX 计算为多次失败。

## 5. Agent 组件边界

### 5.1 Web 层

`AuthController` 只负责请求校验和调用应用服务。`/api/v1/auth/login` 与 `/api/v1/auth/refresh` 加入明确的匿名路径清单，其他 `/api/**` 接口继续执行 Bearer Token 认证。

### 5.2 应用层

`AuthApplicationService` 编排以下步骤：

1. 调用 SSPX OAuth2 Token 端点。
2. 标准化 token type 和到期时间。
3. 使用 access token 查询 SSPX 当前用户。
4. 将 SSPX 身份转换为 Agent 身份。
5. 输出 Agent 自有登录响应，不透传 SSPX 原始对象。

### 5.3 SSPX 适配层

OAuth Token 请求使用 `application/x-www-form-urlencoded`：

- `grant_type=password` 或 `grant_type=refresh_token`
- `client_id`
- `client_secret`
- `scope=profile`
- 密码登录时使用 `name` 与 `pwd`
- 刷新时使用 `refresh_token`

敏感字段放入请求体，不拼接到 URL。HTTP 日志不得输出请求体、Authorization、access token、refresh token 或客户端密钥。

### 5.4 配置

普通集成配置保存在 Nacos，密钥通过运行环境注入：

```properties
integration.sspx.base-url=http://127.0.0.1:9092
integration.sspx.oauth.client-id=${SSPX_OAUTH_CLIENT_ID}
integration.sspx.oauth.client-secret=${SSPX_OAUTH_CLIENT_SECRET}
```

仓库只提供变量名和占位说明，不保存真实值。生产环境应改用 Kubernetes Secret、Vault 或同等级密钥设施。

## 6. 租户语义

SSPX 的外部合同继续使用 `companyId`，Agent 在身份适配层完成一次语义转换：

```text
SspxCurrentUser.companyId → AgentIdentity.tenantId → TenantContext.tenantId
```

约束如下：

- `companyId` 不进入 Agent 的领域对象、业务 DTO 和数据库字段。
- Agent 相关表统一使用 `tenant_id`。
- 前端不发送 `X-Company-Id`。
- 现有请求即使携带 `X-Company-Id`，也不能成为可信租户来源。
- 受保护请求完成 Token 认证后，直接用 `AgentIdentity.tenantId` 建立租户上下文。

## 7. Electron 会话边界

### 7.1 渲染进程

Vue 登录页包含登录名、密码、显示或隐藏密码、提交状态和错误提示。登录提交后立即清理密码字段。渲染进程只接收不含 Token 的 `AuthState`：

```ts
type AuthState =
  | { status: 'unauthenticated' }
  | { status: 'offline'; user?: AuthenticatedUser }
  | { status: 'authenticated'; user: AuthenticatedUser }
```

`AuthenticatedUser` 使用 `tenantId`，不再使用 `companyId`。

### 7.2 主进程

Electron 主进程负责登录网络请求、Token 保存、会话恢复、刷新和退出。Token 不通过 IPC 返回给 Vue，也不保存到 `localStorage`。

会话使用 Electron `safeStorage` 加密，并写入 `app.getPath('userData')` 下的专用文件。文件只包含加密后的 token 集合和最少的到期元数据，不包含密码。

### 7.3 启动恢复

应用启动时：

1. 没有加密会话：进入未登录状态。
2. 有会话：通过 Gateway 调用 `whoami`。
3. 校验成功：使用服务端身份恢复登录。
4. access token 失效：调用 Agent 刷新接口一次，再重新校验。
5. 刷新失败：删除本地会话，显示登录窗口。
6. 网络不可用：保留加密会话并进入离线状态，不把网络错误当作退出登录。

### 7.4 退出

本阶段退出登录立即清除 Electron 加密会话和内存身份，并返回未登录宠物状态。SSPX 的 OAuth revoke 与 Gateway 自定义 `user:Bearer` 缓存是否同时失效需要独立验证，因此本阶段不声明已经实现服务端全局注销。

## 8. 前端交互

- 删除“演示登录”按钮与演示用户。
- 登录窗口沿用现有小鹿视觉样式。
- 登录按钮在请求期间禁用，避免重复提交。
- Enter 可以提交表单。
- 错误信息显示在表单内，不弹出系统对话框。
- 登录成功后隐藏登录窗口并打开对话窗口。
- 宠物和托盘菜单根据主进程广播的真实认证状态更新。

## 9. 验证策略

### 9.1 自动验证

Agent 使用模拟 SSPX 响应验证：

- 登录成功及 `companyId → tenantId`。
- 错误账号或密码的统一映射。
- 账号状态异常。
- SSPX 超时与畸形响应。
- 密码登录不重试。
- refresh token 成功与失败。
- 匿名路径仅包含登录和刷新接口。

Electron 验证：

- 登录成功后写入加密会话。
- 登录失败不写文件。
- Token 不进入渲染进程认证状态。
- 重启恢复、刷新、离线保留和退出清理。

### 9.2 人工联调

使用专用测试账号验证：

1. Postman 调用 Agent 登录接口。
2. 使用返回 Token 通过 Gateway 调用 `whoami`。
3. 桌面登录窗口完成真实登录。
4. 关闭并重新打开应用后自动恢复。
5. 错误密码不会保存会话。
6. 退出后本地认证状态被清除。

真实账号、密码、Token 和 OAuth 客户端密钥不得进入测试代码、提交记录、截图文档或日志样例。

## 10. 完成标准

- 可以使用正常 SSPX 账号密码登录桌面助手。
- 登录成功身份中的 `companyId` 在 Agent 边界转换为 `tenantId`。
- 前端不再传递租户请求头。
- Token 经 Windows 系统能力加密保存，应用重启可以恢复登录。
- 登录后的业务请求仍通过现有 Gateway。
- SSPX 和 Gateway 仓库保持无改动。
- 前后端构建、针对性自动测试和真实账号人工联调均通过。
