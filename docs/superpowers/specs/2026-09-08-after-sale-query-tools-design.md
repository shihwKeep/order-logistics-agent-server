# 售后单查询与详情展示设计

## 1. 目标与范围

第一版接入 `D:\GitCode\aftersale`，为坐席 Agent 提供售后单列表查询和详情展示能力。

本期只读，不提供创建售后、取消工单、状态变更、审核、退款、财务提交或其他写操作。

支持的查询条件：

- 售后工单号；
- 原订单号；
- 客户编号；
- 客户姓名；
- 可选的售后单创建时间范围。

不支持手机号、退货运单号、状态等扩展筛选条件作为第一版模型工具参数。

## 2. 总体架构

调用链如下：

```text
用户自然语言
  -> Agent 售后查询工具
  -> AfterSaleGateway（校验、超时、重试、熔断）
  -> aftersale Agent 专用只读接口
  -> 售后数据库
  -> 有界、脱敏 DTO
  -> 模型最小事实摘要
  -> 完整业务卡片通过 SSE 直接发送前端
```

涉及三个仓库：

- `D:\GitCode\aftersale`：新增 Agent 专用只读接口、可信身份校验、权限范围、查询适配和脱敏 DTO；
- `D:\GitCode\order-logistics-agent-server`：新增售后领域模型、Feign 客户端、Gateway、模型工具、卡片白名单动作和 SSE 业务结果；
- `D:\GitCode\order-logistics-agent-web`：新增售后列表卡片、详情卡片和“查看详情”动作。

旧的 `/aftersale/queryAfterSale` 和 `/aftersale/detail` 保持不变。Agent 不直接消费旧接口的大对象，避免依赖 UpperCamelCase 旧契约及暴露内部敏感字段。

## 3. aftersale 内部只读接口

### 3.1 接口

```text
POST /internal/agent/after-sales/search
POST /internal/agent/after-sales/detail
```

搜索请求：

```json
{
  "identifierType": "AFTER_SALE_CODE | ORDER_CODE | CUSTOMER_CODE | CUSTOMER_NAME",
  "identifier": "业务编号或客户姓名",
  "startTime": "可选的 ISO-8601 日期时间",
  "endTime": "可选的 ISO-8601 日期时间"
}
```

详情请求：

```json
{
  "afterSaleCode": "售后工单号"
}
```

详情接口只接受售后工单号，不接受数据库 `afterSaleId`。服务端先在当前访问范围内解析工单号，再读取详情，防止内部主键枚举。

### 3.2 查询规则

- 售后工单号按现有业务规则进行前缀匹配；
- 原订单号和客户编号精确匹配；
- 客户姓名模糊匹配，至少 2 个字符；
- 时间范围作用于 `after_sale.CreateTime`；
- 时间允许只提供开始或结束边界；两者同时存在时，开始时间不能晚于结束时间；
- 结果按售后单 ID 倒序，即最新工单优先；
- 返回真实总数，卡片最多返回 5 条，超过时 `truncated=true`；
- 原单商品和换货商品分别最多返回 20 条，超过时返回各自的截断标记。

查询使用 Agent 专用 Mapper/查询参数，不复用可由调用方写入 `orgIds`、`loginId` 等权限字段的旧请求对象。

### 3.3 可信身份与权限

内部请求必须携带：

```text
X-Agent-Internal-Token
X-Agent-Tenant-Id
X-Agent-User-Id
X-Agent-Org-Id
X-Agent-Request-Id
```

- Token 使用常量时间比较；
- 租户、用户、组织必须为正整数，requestId 必须为 UUID；
- 身份只从拦截器写入的请求属性读取，Controller 不接受请求体自报身份；
- 当前 `aftersale` 为丝路单租户数据模型，租户头必须与 Nacos 中配置的支持租户一致，否则失败关闭；
- 组织范围沿用售后系统现有查询规则：由可信组织 ID 获取本组织及下级组织；请求体不能传入或扩大组织范围；
- 详情也必须执行同一访问范围校验，不能直接调用未校验权限的旧 `detail(afterSaleId)`。

## 4. 稳定响应契约

所有新接口使用 lowerCamelCase DTO，金额字段统一为整数“分”，命名以 `InFen` 结尾。

### 4.1 列表响应

```text
matchedBy
total
truncated
queriedAt
items[]
  afterSaleCode
  statusCode
  statusText
  createdAt
  finished
  finishedAt
  customerDisplayName
  customerCode
  orderCode
  returnLogisticsCode
  assigneeDisplayName
```

`customerDisplayName` 和处理人姓名在 aftersale 边界完成脱敏，Agent 不接收原始姓名。

### 4.2 详情响应

```text
afterSaleCode
statusCode
statusText
createdAt
finished
finishedAt
customerDisplayName
customerCode
orderCode
exchangeOrderCode
returnLogisticsCode
returnRemark
items[]
exchangeGoods[]
refundSummary
itemsTruncated
exchangeGoodsTruncated
queriedAt
```

原单售后商品包含：商品名、SKU、规格、售后原因、原单数量、收货数量、退款数量、换货数量和原物寄回数量。

换货商品包含：商品名、SKU、数量、单价（分）和小计（分）。

退款汇总只返回已确认单位的业务金额，例如退现金、退预存、预存退现金。内部接口从数据库整数金额字段映射，不直接透传旧 `AfterSaleReturnCostVO` 中单位含义不一致的 `Long/Double` 字段。

以下字段不进入 Agent 契约：银行账号、账户名、详细收货地址、电话内部 ID、数据库主键、财务凭证地址、内部备注 JSON 和其他写操作字段。

## 5. Agent 工具与按需详情

### 5.1 模型工具

新增两个只读工具：

```text
search_after_sales
get_after_sale_detail
```

`search_after_sales` 接受明确的匹配类型、业务值和可选时间范围。模型只接收结果数量、售后工单号、状态和是否截断等最小事实，不接收完整商品、原因与退款数据。

`get_after_sale_detail` 供用户在自然语言中直接询问某个售后单详情时使用。

### 5.2 前端白名单动作

售后列表每条卡片提供“查看详情”。前端发送：

```text
QUERY_AFTER_SALE_DETAIL + afterSaleCode
```

`ChatActionDispatcher` 校验动作类型、工单号和灰度权限后，直接调用与模型工具相同的详情应用服务。该动作绕过模型工具选择，但不绕过可信身份、访问范围、内部 Token、熔断和响应校验。

详情结果作为新的助手消息卡片追加到对话中，列表消息本身不做可变状态更新，保证历史恢复后仍可重放。

## 6. 前端展示

### 6.1 列表卡片

查询结果标题采用左侧两行布局：

```text
售后查询结果
共 N 条 · 当前展示 M 条
```

每条售后摘要卡片显示工单号、状态、创建时间、是否结案、客户脱敏名称与编号、原订单号、退货运单号和当前处理人。列表最多展示 5 条。

### 6.2 详情卡片

详情按以下分区折叠展示：

1. 基本信息；
2. 售后说明；
3. 原单售后商品；
4. 换货商品；
5. 退款汇总。

空字段显示“未提供”，空数组显示对应空状态；长工单号、SKU、原因和说明必须换行，不能撑破窄窗口。卡片不使用 `v-html`，不渲染未经校验的远程图片或链接。

## 7. 可靠性与安全失败

- Agent Gateway 对连接失败、读取超时、HTTP 502/503/504 最多重试 1 次；
- 参数错误、401/403、404、业务空结果和非法响应不重试；
- 搜索与详情使用独立熔断器，避免详情故障拖垮列表查询；
- Feign 原始异常在 Gateway 边界切断，用户只看到“售后查询服务暂时不可用，请稍后重试”；
- 下游返回空关键字段、超过条数上限或未脱敏姓名时，按非法响应失败关闭；新增但尚未识别的状态码安全展示为“状态未知”，同时记录不含业务数据的契约漂移告警，避免新状态导致整笔查询不可用；
- 日志只记录 requestId、操作、匹配类型、结果数量、耗时和失败分类，不记录客户姓名、编号值、售后说明、Token 或下游响应正文。

## 8. 配置与灰度

配置全部进入 Nacos，不写 `application.properties`。

aftersale 侧配置包括：内部接口开关、内部 Token、支持租户、列表上限、详情商品上限。

Agent 侧配置包括：aftersale base URL、内部 Token、连接/读取超时、搜索与详情熔断器，以及售后列表/详情工具的 `OFF | ALLOWLIST | ALL` 灰度配置。

所有开关默认关闭。先对测试组织开放列表查询，验证后再开放详情，最后扩大灰度范围。

## 9. 验证标准

### aftersale

- Token 与可信身份缺失或错误时拒绝；
- 不允许请求体覆盖身份和组织范围；
- 四种查询类型和可选时间范围结果正确；
- 返回真实总数且最多 5 条；
- 详情必须在相同访问范围内按工单号解析；
- 姓名脱敏、金额单位和列表截断正确；
- 银行账户、详细地址、内部 ID 不存在于响应 DTO。

### Agent

- Feign 请求头、请求体和响应契约正确；
- 临时故障重试一次，非临时故障不重试；
- 搜索和详情分别受熔断器保护；
- 模型工具只返回有界摘要，完整结果经 SSE 发布；
- 同轮重复工具调用受现有 ToolCallGuard 约束；
- “查看详情”白名单动作不经过模型且仍执行全部权限校验。

### 前端

- 售后列表和详情卡片正确渲染；
- 总数和当前展示数量不混淆；
- 详情按钮使用被点击卡片自己的售后工单号；
- 空值、空结果、截断和长文本在窄窗口下排版稳定；
- 用户和助手消息级时间继续显示，业务卡片不重复显示原始查询时间。

### 联调

使用真实测试数据依次验证：售后工单号、原订单号、客户编号、客户姓名、客户加时间范围，以及列表中的“查看详情”。确认前端卡片、模型简短回答、权限范围、服务日志和失败提示一致。
