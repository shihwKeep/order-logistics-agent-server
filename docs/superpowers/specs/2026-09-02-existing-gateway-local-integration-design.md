# 现有 Gateway 本地接入 Agent 设计

## 1. 目标

在不修改 `sspx-server` 和现有 `gateway` 源码、配置文件的前提下，让桌面宠物前端通过现有 Gateway 访问 `order-logistics-agent-server`，并复用现有登录认证链路。

本方案仅服务于本地学习、联调、评测和面试演示，不改变生产系统。

## 2. 约束

- `sspx-server` 与 `gateway` 是现有生产项目，只读复用，不提交任何改动。
- Agent 仍监听本机 `8080` 端口。
- Gateway 本地监听 `8081` 端口。
- 前端只访问 Gateway，不把 Agent 的 `8080` 作为正常业务入口。
- Agent 不信任 Gateway 传入的 `CurrentUser`，必须携带原始 Bearer Token 再向 SSPX 校验身份。
- 租户 ID 由请求头 `X-Company-Id` 传递，Agent 校验它与 SSPX 返回的 `companyId` 一致。

## 3. 本地请求链路

```text
桌面宠物前端 / Postman
        |
        | Bearer Token + X-Company-Id
        v
现有 Gateway :8081
        |
        | 现有认证过滤器：按 local 配置从共享 Redis 校验登录态
        | 临时路由：/agent/**，转发时去掉 /agent
        v
Agent :8080
        |
        | 忽略 CurrentUser，使用原始 Bearer Token 再次校验
        v
SSPX :9092
        |
        v
身份确认 + companyId 租户校验
```

Gateway 与本机 SSPX 的 local 配置连接同一套 Redis Sentinel，并使用相同数据库，因此保持 Gateway 现有的 Redis 登录态校验方式，不切换其认证实现。

## 4. 临时路由

Gateway 启动时通过运行参数注入下面的路由，不写入 Gateway 仓库：

| 项目 | 值 |
| --- | --- |
| 路由 ID | `order-logistics-agent-local` |
| 匹配路径 | `/agent/**` |
| 目标地址 | `http://127.0.0.1:8080` |
| 过滤器 | `StripPrefix=1` |

示例：外部请求 `/agent/api/v1/system/whoami`，Agent 实际收到 `/api/v1/system/whoami`。`Authorization`、`X-Company-Id` 和 `X-Request-Id` 等请求头按 Gateway 现有行为继续传递。

## 5. 安全边界

Gateway 完成入口认证和路由治理，但它生成的 `CurrentUser` 只作为兼容信息，不能作为 Agent 的可信身份来源。Agent 使用原始 Token 调用 SSPX 重新确认身份，随后执行租户一致性校验。

因此，即使有人绕过 Gateway 直接访问 Agent 并伪造 `CurrentUser`，也不能伪造有效登录身份。后续仍应通过防火墙、容器网络或安全组限制 Agent 端口只允许 Gateway 访问，形成网络层与应用层双重保护。

## 6. 故障行为

- Gateway 无法连接共享 Redis：沿用 Gateway 现有失败行为，不增加绕过认证的降级。
- SSPX 不可用：Agent 返回 `503 AUTH_SERVICE_UNAVAILABLE`。
- Token 无效：Agent 返回 `401 AUTH_TOKEN_INVALID`。
- 请求租户与登录用户租户不一致：Agent 返回 `403 TENANT_ACCESS_DENIED`。
- Agent 未启动：Gateway 转发失败，不回退到其他服务。

## 7. 验收范围

通过 Gateway 地址分别验证：

1. 有效 Token、正确租户访问成功。
2. 缺少 Token 被拒绝。
3. 无效 Token 被拒绝。
4. 错误租户被拒绝。
5. SSPX 停止时返回认证服务不可用。
6. 直接伪造 `CurrentUser` 不能绕过 Agent 的 Token 校验。

测试过程不得把真实 Token、账号或个人信息写入 Git、截图文档或日志样例。
