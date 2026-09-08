# 客户查询与客户订单工具上线核验

## 1. 能力边界

- `customerservice` 负责按当前坐席数据权限精确查询客户。
- Agent 只允许按完整客户编号或完整客户姓名查询，不支持手机号查询。
- 按客户查询订单时，Agent 必须先用客户编号重新解析出唯一且有权访问的客户，再把内部 `customerId` 交给 order 内部接口。
- order 查询同时应用 `CustomerId` 条件与原有订单数据权限条件。
- 客户内部 ID 不进入模型文本、SSE 卡片、历史结果快照和业务日志。
- 所有接口均为只读能力。

## 2. Nacos 配置

以下配置只放入对应服务的 Nacos 配置，不写入仓库内的 `application.properties` 或 YAML。真实 Token 由运行环境变量提供，两个服务必须使用同一个 `AGENT_INTERNAL_TOKEN`。

### 2.1 customerservice

```properties
# Agent 客户内部接口：首次部署保持关闭
agent.customer.internal-api.enabled=false
agent.customer.internal-api.token=${AGENT_INTERNAL_TOKEN}
agent.customer.internal-api.query-limit=10
```

`query-limit=10` 表示最终最多返回 10 张客户卡片；服务内部会多读取一条用于判断是否截断。

### 2.2 order-logistics-agent-server

```properties
# Agent -> customerservice
integration.customer.base-url=${CUSTOMER_SERVICE_BASE_URL:http://127.0.0.1:8083}
integration.customer.internal-token=${AGENT_INTERNAL_TOKEN}

# 客户查询 Feign 超时；Feign 自身禁止自动重试，瞬时故障由 Gateway 最多重试一次
spring.cloud.openfeign.client.config.agent-customer-search.connect-timeout=1000
spring.cloud.openfeign.client.config.agent-customer-search.read-timeout=3000
spring.cloud.openfeign.client.config.agent-customer-search.logger-level=basic

# 按客户查询订单使用独立 Feign 客户端，但复用现有 order 地址和内部 Token
spring.cloud.openfeign.client.config.order-agent-customer-search.connect-timeout=1000
spring.cloud.openfeign.client.config.order-agent-customer-search.read-timeout=5000
spring.cloud.openfeign.client.config.order-agent-customer-search.logger-level=basic

# 客户服务熔断参数
integration.customer.resilience.sliding-window-size=20
integration.customer.resilience.minimum-number-of-calls=10
integration.customer.resilience.permitted-calls-in-half-open-state=3
integration.customer.resilience.failure-rate-threshold=50
integration.customer.resilience.open-state-wait-duration=30s
integration.customer.resilience.call-timeout=5s

# 模型客户查询工具：首次部署保持关闭
agent.tool.customer.enabled=false
agent.tool.customer.rollout-mode=OFF
agent.tool.customer.allowed-org-ids=

# 模型工具和客户卡片“查看订单”动作共用此独立开关
agent.tool.customer-order.enabled=false
agent.tool.customer-order.rollout-mode=OFF
agent.tool.customer-order.allowed-org-ids=
```

order 侧沿用已有的 `agent.order.internal-api.*`、`integration.order.*` 与内部 Token 配置，不增加第二套鉴权密钥。

## 3. 灰度顺序

1. 先部署 customerservice、order、Agent 和桌面端代码，所有新增开关保持关闭。
2. 在 customerservice 设置 `agent.customer.internal-api.enabled=true`，重启 customerservice，并通过内部网络验证未带 Token、错误 Token 均返回 401。
3. 将 `agent.tool.customer` 设置为 `enabled=true`、`rollout-mode=ALLOWLIST`，只填写测试坐席所属组织 ID，重启 Agent。
4. 验证客户查询后，再将 `agent.tool.customer-order` 以同样的组织白名单方式开启并重启 Agent。
5. 观察错误率、超时、熔断和越权核验结果后，再将灰度组织逐步扩大；全量时使用 `rollout-mode=ALL`。

当前开关对象在应用启动时绑定；修改 Nacos 后应重启对应服务，不能假设条件 Bean 和工具清单会热创建。

## 4. 回滚

1. 优先把 Agent 的 `agent.tool.customer-order.enabled` 和 `agent.tool.customer.enabled` 设置为 `false`，然后重启 Agent，使模型请求中完全不再出现相应工具定义，卡片动作也会失败关闭。
2. 如需进一步隔离，再把 customerservice 的 `agent.customer.internal-api.enabled` 设置为 `false` 并重启 customerservice。
3. 不需要删除数据库数据；本功能没有数据库迁移，也不执行写业务数据操作。

## 5. 自动化验证

```powershell
# customerservice（该旧项目使用 JDK 17；显式开启测试）
$env:JAVA_HOME='C:\Users\shwfo\.jdks\jbr-17.0.14'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn "-DskipTests=false" "-Dmaven.test.skip=false" test

# order（MyBatis/OGNL 在 JDK 17 下需要开放 java.util）
$env:JAVA_HOME='C:\Users\shwfo\.jdks\jbr-17.0.14'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn "-DargLine=--add-opens=java.base/java.util=ALL-UNNAMED" test

# Agent
mvn test

# 桌面端
npm test
npm run typecheck
npm run build
```

## 6. 人工验收清单

- 完整客户编号能返回客户卡片；完整姓名重名时返回多张卡片供坐席确认。
- 无权限客户与不存在客户对外表现一致，不泄露客户是否存在。
- 卡片仅展示脱敏名称、客户编号、等级、客户类型和归属类型。
- 客户卡片“查看订单”只发送 `customerCode`，不发送身份、Token 或内部 `customerId`。
- 自然语言“查询客户 C001 的订单”和卡片动作均复用同一客户解析与订单查询链路。
- 客户订单继续复用富订单卡片，订单卡片“查看物流”仍可正常工作。
- 重启桌面端或恢复历史后，客户卡片、订单卡片及动作仍可用。
- 日志、模型文本、SSE 与历史结果中不存在 Token、完整手机号、详细地址、证件号、生日或内部客户 ID。
- 客户服务不可用、订单服务不可用、熔断打开时均返回安全提示，不输出下游响应正文。
