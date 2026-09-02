# order-logistics-agent-server

享佳智能坐席助手后端服务。

## 本地认证配置

SSPX OAuth 客户端信息不得写入源码或提交真实值。Agent 的 Nacos 配置使用环境变量占位：

```properties
integration.sspx.base-url=http://127.0.0.1:9092
integration.sspx.connect-timeout=1000
integration.sspx.read-timeout=2000
integration.sspx.oauth.client-id=${SSPX_OAUTH_CLIENT_ID}
integration.sspx.oauth.client-secret=${SSPX_OAUTH_CLIENT_SECRET}
```

在 IDEA 的 `OrderLogisticsAgentServerApplication` 运行配置中设置：

```text
SSPX_OAUTH_CLIENT_ID=本地测试客户端ID
SSPX_OAUTH_CLIENT_SECRET=本地测试客户端密钥
```

`.env.example` 只提供变量名；本地真实值应保存在已被 Git 忽略的 `.env.local` 或 IDE 私有运行配置中。

## 本地接口

- `POST http://127.0.0.1:8080/api/v1/auth/login`：账号密码登录。
- `POST http://127.0.0.1:8080/api/v1/auth/refresh`：刷新访问令牌。
- `GET http://127.0.0.1:8081/agent/api/v1/system/whoami`：通过现有 Gateway 验证当前身份。

登录接口由桌面端主进程直连 Agent；登录后的受保护业务接口通过 Gateway 访问。租户 ID 从 SSPX 已认证身份中的 `companyId` 映射为 Agent 内部 `tenantId`，客户端不传租户请求头。
