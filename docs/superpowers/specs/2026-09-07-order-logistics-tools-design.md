# 订单与物流查询工具生产设计

## 1. 背景

当前系统已经具备桌面端登录、SSPX 身份认证、Spring AI 流式对话、SSE 结构化结果、商品查询工具、MySQL 会话历史、Redis 短期记忆和会话级长期滚动摘要。

订单数据位于 `D:\GitCode\order`。该服务已经维护订单、商品明细、运单关系和订单数据权限，并通过 Feign 调用 `D:\GitCode\silu-logistics` 查询物流轨迹。下一阶段需要在现有真实业务边界上建设生产可用的订单与物流查询工具。

## 2. 已确认决策

1. Agent 不直接调用 `silu-logistics`，而是调用 `order` 的 Agent 专用只读接口。
2. `order` 负责订单定位、数据权限、运单关系和物流聚合。
3. 第一版只支持订单号、外部订单号和运单号精确查询。
4. 客户姓名、手机号查订单等客户查询能力，等后续取得可信 `customerId` 后再开放。
5. 普通订单查询只返回物流概要；明确查询物流或点击“查看物流”时才查询完整轨迹。
6. 订单与物流使用 SSE 结构化卡片，模型只获得有界的压缩结果。
7. 实时订单状态和物流位置不能成为长期摘要或未来跨会话记忆中的永久事实。
8. 非敏感运行配置进入 Nacos，密钥只由环境变量注入。

## 3. 目标与非目标

### 3.1 目标

- 有严格数据权限的订单精确查询。
- 单订单、多订单、单运单和多运单展示。
- 由 `order` 聚合的实时物流查询。
- 商品、订单、物流和未来工具共用的 ToolContext 与 SSE 发布机制。
- 结构化工具结果持久化和历史卡片恢复。
- 支持“它到哪了”等会话内订单引用。
- 超时、重试、熔断、部分降级、限流、审计和端到端验证。

### 3.2 非目标

- 按客户姓名或手机号模糊搜索订单。
- 订单创建、修改、撤单、退款或补发等写操作。
- Agent 绕过 `order` 直接调用物流服务。
- 查询或展示完整地址、手机号、派送员电话等敏感信息。
- 真实订单详情页跳转。
- 客户查询、知识库 RAG 和跨会话用户记忆。
- 在 Agent 长时间缓存订单状态或物流轨迹。

## 4. 总体架构

```text
桌面端 Bearer Token
    │
    ▼
Agent 身份认证
    ├── tenantId
    ├── userId
    └── orgId
    │
    ▼
Spring AI 订单/物流工具
    │  内部服务凭证 + 可信身份头 + requestId
    ▼
order Agent 专用只读接口
    ├── 解析订单标识
    ├── 计算订单访问范围
    ├── 在权限范围内精确查询
    ├── 批量组装商品和运单概要
    └── 需要轨迹时调用 silu-logistics
            ├── 批量精确查询 ES
            ├── 按已知承运商定向刷新
            └── 返回脱敏轨迹
    │
    ▼
Agent
    ├── 压缩结果返回模型
    ├── 完整结果通过 SSE result 发给前端
    └── 收尾时持久化结构化结果
```

## 5. 身份与信任边界

### 5.1 外部身份

桌面端只提交用户 Bearer Token。Agent 认证后生成 `AgentIdentity`。模型和用户消息都不能提供或覆盖 `tenantId`、`userId`、`orgId`。

### 5.2 内部服务身份

Agent 调用 `order` 时携带：

```text
X-Agent-Internal-Token
X-Agent-Tenant-Id
X-Agent-User-Id
X-Agent-Org-Id
X-Agent-Request-Id
```

`order` 必须先校验内部 Token，再读取身份头。身份字段不能放入模型可构造的请求正文。内部 Token 来自环境变量，Nacos 只保存环境变量占位符。

该 Token 不是用户身份凭证，而是 Agent 服务调用 `order` 的服务身份凭证。生产环境还必须限制内网访问、使用 TLS，并支持凭证轮换；仅有身份请求头而没有通过服务身份校验时一律拒绝。

### 5.3 授权原则

- `userId` 用于查询现有订单权限。
- `orgId` 只能缩小权限，不能扩大权限。
- `tenantId` 用于内部身份校验、审计和 Agent 侧隔离。
- 当前 `orders` 主表没有稳定的 `TenantId` 行级字段，订单真实隔离依赖 `userId + UserOrgId + 订单权限服务`。
- 权限服务异常时失败关闭，不能降级为无条件查询。
- 不存在和无权访问对外统一为“没有找到可访问的订单”。

## 6. 订单访问范围

`order` 新增不可变访问范围：

```text
AgentOrderAccessScope
├── userId
├── loginOrgId
├── accessMode: GLOBAL | ORG_SCOPE | SELF
└── allowedOrgIds
```

生成规则：

1. 使用可信 `userId` 调用现有 `privilegeFeignService.getOrderPrivilege(userId)`。
2. 明确具有全局权限时使用 `GLOBAL`。
3. 返回可访问组织时使用 `ORG_SCOPE`。
4. 明确没有组织权限时使用 `SELF`。
5. 超时、异常或非法响应时拒绝执行查询。

现有订单列表 SQL 的权限条件依赖调用方正确设置 `isSelf`。Agent 查询不能复用这种容易被遗漏的调用方式，应新增专用 Mapper，并保证每次 SQL 必须包含一个有效权限分支：

```sql
-- 三者必须且只能选择一个有效分支
GLOBAL
OR UserOrgId IN (...)
OR UserId = :userId
```

没有有效权限分支时，应用层不得调用 Mapper。

## 7. 内部接口契约

### 7.1 订单精确查询

```http
POST /internal/agent/orders/search
```

请求：

```json
{
  "identifier": "用户输入的编号",
  "identifierType": "AUTO"
}
```

`identifierType` 支持：

```text
AUTO
ORDER_CODE
OUTER_ORDER_CODE
LOGISTICS_CODE
```

`AUTO` 依次精确匹配内部订单号、外部订单号和运单号。禁止 `%keyword%` 模糊扫描。

响应：

```json
{
  "matchedBy": "ORDER_CODE",
  "total": 1,
  "truncated": false,
  "queriedAt": "2026-09-07T14:30:00+08:00",
  "items": [
    {
      "orderCode": "O123",
      "outerOrderCode": "OUT123",
      "statusCode": 80,
      "statusText": "在途",
      "orderTime": "2026-09-07 12:00:00",
      "customerDisplayName": "石**",
      "payAmountInFen": 12900,
      "goodsTotalCount": 2,
      "goods": [
        {
          "goodsName": "商品名称",
          "skuCode": "SKU001",
          "specification": "规格",
          "quantity": 2
        }
      ],
      "carrierName": "顺丰速运",
      "logisticsCodes": ["SF123456"]
    }
  ]
}
```

限制为最多 5 个订单，每个订单最多 3 项商品摘要。金额统一以分传输。

数据库内部 `orderId` 只允许在 `order` 服务内部完成订单、商品、运单的批量关联，不返回给 Agent、模型或前端。

### 7.2 订单物流查询

```http
POST /internal/agent/orders/logistics
```

请求仍使用订单号、外部订单号或运单号。模型不能传裸 `orderId` 绕过订单定位和授权。

响应：

```json
{
  "order": {
    "orderCode": "O123",
    "statusCode": 80,
    "statusText": "在途"
  },
  "queriedAt": "2026-09-07T14:30:00+08:00",
  "partial": false,
  "shipments": [
    {
      "logisticsCode": "SF123456",
      "carrierName": "顺丰速运",
      "resultStatus": "SUCCESS",
      "latestStatusText": "运输中",
      "latestTrace": "快件已到达南京转运中心",
      "traces": [
        {
          "time": "2026-09-07 10:30:00",
          "location": "南京市",
          "description": "快件已到达南京转运中心"
        }
      ]
    }
  ]
}
```

多个运单独立返回。轨迹按时间倒序，每个运单最多 50 条。

## 8. 查询和数据组装

查询流程：

1. 规范化编号：去除首尾空白、限制长度、拒绝控制字符。
2. 取得不可省略的 `AgentOrderAccessScope`。
3. 在权限范围内执行精确匹配。
4. 得到授权订单 ID 集合后，批量查询商品摘要、金额、运单和承运商。
5. 超过上限时返回前 5 条并设置 `truncated=true`。

订单基本信息、商品摘要、运单和承运商应按订单 ID 集合批量读取，不能对每张卡依次调用订单详情接口形成 N+1。

开发前必须核对内部订单号、外部订单号、运单号及权限过滤字段的现有索引。缺少可支持“精确编号 + 权限范围”查询的索引时，应先补索引并通过 `EXPLAIN` 验证执行计划；不能用全表扫描换取功能上线。

Agent 专用 DTO 不包含：收货手机号和电话 ID、省市区街道及详细地址、发货要求、审核备注、财务备注、派送员姓名电话和无关内部操作字段。

## 9. `silu-logistics` 内部轨迹接口

现有 ES 未命中逻辑可能依次尝试多个承运商，现有 `isSecret=true` 路径也没有明确完成电话替换。Agent 场景新增：

```http
POST /internal/agent/logistics/tracks/query
```

请求由 `order` 根据已授权订单生成：

```json
{
  "shipments": [
    {
      "logisticsCode": "SF123456",
      "carrierId": 12
    }
  ],
  "maxTraceNodes": 50
}
```

这里的 `carrierId` 是 `data_carrier.Id`，不是顺丰、EMS、德邦、京东等供应商类型。`silu-logistics` 必须根据该 ID 读取自己的 `data_carrier.Type` 后选择刷新器，禁止把记录主键直接当作承运商类型比较；记录不存在、已删除或类型不受支持时，不执行任何猜测式刷新。

处理规则：

1. 批量精确查询 ES。
2. ES 命中时不访问承运商。
3. ES 未命中时只调用已知 `carrierId` 对应的承运商。
4. 承运商刷新使用独立时间预算。
5. 刷新后只重新查询一次 ES。
6. 每个运单独立返回结果状态，一个失败不影响其他运单。
7. 使用新的最小 DTO，不直接暴露 `RouteDO`。
8. 该内部接口必须校验独立的 `order → silu-logistics` 服务凭证，不能复用用户 Bearer Token 或 Agent 到 `order` 的内部 Token。

运单结果状态：

```text
SUCCESS
NOT_SHIPPED
NO_TRACE
REFRESH_TIMEOUT
DOWNSTREAM_UNAVAILABLE
UNSUPPORTED_REFRESH
```

## 10. Agent 工具与通用上下文

### 10.1 工具定义

```text
search_orders(identifier, identifierType)
get_order_logistics(identifier, identifierType)
```

`search_orders` 返回订单卡片和物流概要。`get_order_logistics` 返回订单概要及完整轨迹。

### 10.2 通用上下文

把当前商品专用 `ProductToolRequestContext` 重构为：

```text
AgentToolRequestContext
├── requestId
├── AgentIdentity
└── ToolOutputPublisher
```

所有业务工具从 Spring AI `ToolContext` 取得同一个可信上下文。`ChatTurnRunner` 只创建通用上下文，不感知具体商品、订单、物流、客户或知识库工具。

### 10.3 两份输出

每次工具执行生成：

1. 完整前端结构，通过 SSE `result` 发布。
2. 有界文本结果，返回模型用于生成一两句说明。

禁止把完整订单列表或轨迹 JSON 送入模型。

### 10.4 调用保护

- 单轮最多 3 次业务工具调用。
- 同一 `requestId + toolName + canonicalArguments` 只执行一次。
- 参数、权限和不存在等业务结果不重试。
- 重复模型调用复用首次结果，不重复发布卡片。

## 11. SSE 协议

新增结果类型：

```text
order-list
logistics-timeline
```

订单查询事件：

```text
session
status: GENERATING
status: QUERYING_ORDER
result: order-list
delta: 简短说明
done
```

物流查询事件：

```text
session
status: GENERATING
status: QUERYING_LOGISTICS
result: logistics-timeline
delta: 简短说明
done
```

订单成功但物流失败时仍发送订单结构，并把物流标为部分失败，不能把整个聊天轮次处理为模型失败。

## 12. 前端交互

### 12.1 订单卡片

展示订单号、外部订单号、状态、下单时间、脱敏客户名称、应付金额、最多 3 项商品摘要、总商品数、承运商和运单号。

卡片提供“查看物流”和暂时禁用的“订单详情”按钮。

### 12.2 物流时间线

- 一个运单一个折叠区。
- 最新轨迹在前。
- 默认显示最近 3 条，可展开全部。
- 显式展示查询时间。
- 区分未发货、暂无轨迹、刷新超时和服务不可用。
- 前端不把后端文本当 HTML 渲染。

### 12.3 结构化按钮动作

点击“查看物流”时发送结构化动作，不重新让模型猜测按钮意图：

```json
{
  "conversationId": "...",
  "message": "查看订单 O123 的物流",
  "action": {
    "type": "QUERY_ORDER_LOGISTICS",
    "orderCode": "O123"
  }
}
```

Agent 必须重新认证用户，`order` 必须重新授权。普通自然语言仍由模型选择工具。

结构化 `action` 必须由独立的确定性动作分发器完成参数白名单校验并直接执行对应工具，不再经过模型进行工具选择；结果说明可由后端固定模板生成，避免按钮点击被模型误判或改写参数。

## 13. 结构化结果持久化

新增 `agent_message_result` 保存 UI 快照：

| 字段 | 说明 |
|---|---|
| `id` | 自增主键 |
| `tenant_id` | 租户 ID |
| `user_id` | 坐席用户 ID |
| `conversation_id` | 会话 ID |
| `request_id` | 本轮请求 ID |
| `message_id` | 对应助手消息 ID |
| `result_sequence` | 同一回答中的结果顺序 |
| `tool_name` | 产生结果的工具名称 |
| `kind` | 结果类型 |
| `schema_version` | 前端结构版本 |
| `payload_json` | 已裁剪脱敏的结构化快照 |
| `queried_at` | 业务数据实际查询时间 |
| `created_at` | 创建时间 |

工具执行时先把结果放入当前 `ChatTurnExecution`。只要工具已经成功产生并向客户端发布业务结果，即使后续模型输出失败、超时或客户端断开，收尾逻辑仍应把结果和对应的终态助手消息一起持久化，不能因模型失败丢失已展示的卡片。助手消息和结果快照必须在同一数据库事务内提交。

表约束至少包括：`message_id` 外键关联 `agent_message(message_id)`、`conversation_id` 外键关联会话、`(request_id, result_sequence)` 唯一约束，以及历史批量加载所需的 `(tenant_id, user_id, conversation_id, message_id)` 索引。`payload_json` 使用有界 JSON/MEDIUMTEXT。结果必须在 SSE 发布前完成脱敏、裁剪、序列化和大小校验；超过上限时生成有界降级结果并记录安全审计，不能发布一个之后无法持久化的超大快照。

一条助手消息可以包含多个结果。保存的是当时查询快照，必须包含 `queriedAt`；刷新应产生新回答和新结果，不能静默改写历史结果。

历史读取必须按 `kind + schemaVersion` 校验和反序列化。单条旧版本或损坏结果只降级为普通文本消息并记录告警，不能导致整个会话历史接口失败。

## 14. 会话内业务引用与新鲜度

用户可能在订单查询后说“查一下它的物流”。上下文组装需要从最近结构化结果中派生唯一、明确的订单引用：

```text
【当前会话业务引用】
最近明确订单号：O123。
这是实体引用，不代表订单状态仍然有效；
涉及当前状态、金额或物流时必须重新调用工具。
```

规则：

- 最近结果只有一个订单时可以建立引用。
- 多个订单时不得自动选中，必须由用户补充或点击卡片。
- 引用只帮助解析代词，不能替代实时查询。
- 会话摘要可以保留订单号和待处理任务，但不能把订单状态或轨迹保存为长期有效事实。
- 未来跨会话记忆不保存实时订单状态、金额和物流位置。

模型侧压缩结果必须显式携带 `queriedAt` 和“仅代表该查询时刻”的说明；摘要提示词、摘要结构校验和回归测试共同保证实时状态不会被写成长期事实。

## 15. 超时、重试、熔断和降级

所有具体参数进入 Nacos：

- Agent 到 `order` 的订单查询和物流查询使用不同读取超时。
- `order` 到 `silu-logistics` 的 ES 查询和承运商刷新使用不同时间预算。
- 只对幂等查询的瞬时网络故障最多重试一次。
- 权限、参数、不存在等业务结果不重试。
- 订单和物流使用不同熔断器，物流熔断不能影响订单基本信息。
- 承运商刷新使用独立有界并发隔离。
- Agent 不长时间缓存订单和物流状态，只做单请求去重。

部分降级：

```text
订单信息成功 + 实时轨迹失败
→ 返回订单卡片
→ 物流区显示暂不可用
→ 本轮对话不标记为整体失败
```

## 16. 脱敏与安全

- 新接口不返回完整收货地址和手机号。
- 删除派送员姓名和电话。
- 轨迹描述再次识别并掩码手机号。
- 日志中的订单号和运单号只保留后四位或哈希。
- 不记录内部 Token、Bearer Token、地址和完整轨迹正文。
- 无权访问和不存在使用同一外部错误语义。
- 前端按文本渲染，禁止把物流描述当 HTML。

## 17. 可观测性

传播同一个 `requestId/traceId`：

```text
Agent → order → silu-logistics → ES/承运商
```

结构化日志记录：

```text
requestId
conversationId
toolName
identifierType
accessMode
orderCount
shipmentCount
orderDurationMs
logisticsDurationMs
carrierRefreshTriggered
partial
status
```

租户、用户、订单号和运单号不能作为 Micrometer 指标标签，避免高基数和敏感信息泄露。

## 18. Nacos 配置边界

示例：

```properties
agent.tool.order.enabled=false
agent.tool.logistics.enabled=false

integration.order.base-url=${ORDER_SERVICE_BASE_URL}
integration.order.internal-token=${ORDER_AGENT_INTERNAL_TOKEN}

agent.order.max-results=5
agent.order.max-goods-per-order=3
agent.logistics.max-shipments=10
agent.logistics.max-trace-nodes=50
```

其中 `integration.order.base-url` 只表示配置项形态，实际值必须使用部署环境的订单服务地址或服务发现名称，不能照抄示例端口。`order → silu-logistics` 使用另一组独立的内部凭证配置。

连接超时、读取超时、重试、熔断、并发隔离、结构化结果最大字节数和灰度组织也进入 Nacos。真实密钥只存在进程环境。工具开关关闭时，Agent 不得把对应工具注册给模型。

## 19. 测试要求

### 19.1 `order`

- 三种编号精确查询及 `AUTO` 顺序。
- `GLOBAL`、`ORG_SCOPE`、`SELF` 权限。
- `orgId` 不能扩大权限。
- 无权访问和不存在的统一响应。
- 权限服务故障时失败关闭。
- 多订单、多商品、多运单和结果截断。
- 金额单位、字段白名单和敏感字段缺失。
- 商品和运单批量查询，无 N+1。

### 19.2 `silu-logistics`

- ES 命中不调用承运商。
- ES 未命中只调用已知承运商。
- 刷新后重新读取 ES。
- 多运单部分成功。
- 轨迹排序、数量限制和脱敏。
- ES、承运商超时与熔断。

### 19.3 Agent

- 模型不能注入身份。
- 内部请求头来自 `AgentIdentity`。
- 工具 Schema 和描述。
- 单轮调用上限与同参去重。
- 完整前端结果和模型压缩结果分离。
- SSE 顺序、部分降级和客户端取消。
- 结构化结果持久化及历史恢复。
- 最近唯一订单引用与多订单歧义处理。

### 19.4 前端

- 单订单、多订单、单运单和多运单。
- 未发货、无轨迹、部分失败和服务不可用。
- 查看物流动作和权限重新验证。
- 历史卡片恢复、金额格式化、长文本和窗口缩放。
- HTML 注入防护。

## 20. 发布顺序

1. 新增结构化结果表，功能保持关闭。
2. 发布 `silu-logistics` 内部批量轨迹接口。
3. 发布 `order` Agent 专用查询接口并直接验证权限。
4. 发布 Agent 通用工具上下文、订单和物流工具，开关保持关闭。
5. 发布前端订单卡片、物流时间线和历史恢复。
6. 对测试账号或指定组织灰度开启。
7. 观察错误率、超时率、权限拒绝、部分降级和敏感字段审计。
8. 验证稳定后扩大范围，并保留快速关闭开关。

## 21. 最终验收场景

1. 有权订单号查询成功。
2. 无权订单无法查询。
3. 外部订单号查询成功。
4. 运单号反查订单成功。
5. 未发货订单给出准确提示。
6. 单运单轨迹正常。
7. 多运单分别展示。
8. 物流故障时订单卡片仍展示。
9. 重启并重开会话后历史卡片仍存在。
10. “它到哪了”能在唯一订单引用下重新查询实时轨迹。
11. 多订单情况下不会擅自选择。
12. 日志、模型上下文和前端结果中没有泄露 Token、完整手机号或地址。

## 22. 后续衔接

1. 接入客户查询，并通过可信 `customerId` 扩展客户订单查询。
2. 建设 ES + Milvus 知识库 RAG。
3. 建设跨会话用户记忆，区分稳定偏好和实时业务数据。
4. 统一讲解并完善意图识别、工具选择、并行工具、冲突处理和多工具编排。
5. 增加 Agent 评测集、压测、故障演练和上线复盘。
