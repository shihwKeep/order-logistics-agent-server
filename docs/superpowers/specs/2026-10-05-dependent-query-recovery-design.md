# LangGraph4j 依赖查询与部分失败恢复设计

## 1. 背景与目标

当前复合编排已经可以并列处理客户订单、物流、订单商品和知识库分支，但对“先查客户最近订单，再用该订单查询物流”的多跳问题仍缺少服务端依赖关系。典型表现是：

```text
查询客户 C24101816040001 最近一笔订单的物流状态，并结合售后规则判断
```

系统只能拿到客户订单分支的结果，随后由回答层把订单状态误当成物流查询结果，或者在没有调用售后工具时声称“未查询到售后工单”。这会造成事实来源混淆和不可核验结论。

本设计在 LangGraph4j 第二版基础上补齐：

- 服务端可控的依赖节点：客户订单 -> 最近订单 -> 物流查询；
- 依赖结果的确定性选择和公开订单号传递；
- Redis checkpoint 中保存可恢复的依赖上下文；
- 业务分支失败时返回已取得事实和明确缺失项；
- 失败恢复时跳过已成功且已发布的分支；
- 不让模型凭订单状态推断物流状态，也不让模型虚构售后查询结果。

范围仍仅限内部只读业务、已发布知识库和基于验证事实的普通分析，不接入外部市场数据，不增加写操作。

## 2. 方案选择

### 方案 A：服务端依赖节点（采用）

由 Planner 识别“客户 + 最近订单 + 物流”复合意图，生成一个客户订单基础分支和一个依赖物流分支。基础分支完成后，服务端按订单时间选择最近一笔订单，提取脱敏后的公开订单号，再执行物流查询。知识库分支可与客户订单并行。

优点是依赖关系、权限、参数来源和失败边界都由服务端控制，能够稳定恢复和审计。代价是需要扩展计划模型、工作流状态和测试。

### 方案 B：客户订单接口直接返回物流

会把物流职责耦合到客户订单服务，破坏现有边界，无法复用独立物流查询和其异常分类，不采用。

### 方案 C：让模型拿到订单结果后自行决定下一次工具调用

依赖关系变成模型行为，容易重复调用、错误选单或把状态字段当轨迹事实，不采用。

## 3. 计划模型

在现有 `CompositeQueryIntent` 上增加受控依赖元数据：

```text
source             BUSINESS | KNOWLEDGE | GENERAL
resultKind         order-list | logistics-timeline | knowledge-citations | ...
required           boolean
dependsOnResultKind 可选，例如 order-list
dependencyMode     NONE | LATEST_ORDER
identifierSource   USER_INPUT | RESOLVED_ORDER
```

对于上述示例，Planner 生成：

```text
1. customer-orders(C24101816040001)
   resultKind=order-list, required=true

2. latest-order-logistics
   resultKind=logistics-timeline, required=true
   dependsOnResultKind=order-list
   dependencyMode=LATEST_ORDER
   identifierSource=RESOLVED_ORDER

3. knowledge("售后规则")
   resultKind=knowledge-citations, required=true
```

知识分支和客户订单分支可并行。物流分支必须等待订单分支成功并完成最近订单选择；不得从用户自然语言中猜测订单号，也不得把客户编号直接传给物流 Gateway。

如果用户同时要求售后状态，只有计划中存在售后查询意图且有合法工单号/订单号时才调用售后工具。仅有售后规则分析时，只查知识库；不得输出“未查询到售后工单”等未经查询的事实。

## 4. 最近订单选择规则

服务端从成功的 `CustomerOrderQueryResult` 中选择最近订单：

1. 优先使用服务端返回的制单时间/创建时间；
2. 忽略缺少公开订单号的记录；
3. 时间相同时按公开订单号稳定排序；
4. 只把公开订单号写入依赖上下文和后续 Gateway 参数；
5. 不把内部订单主键、手机号、地址或完整原始响应写入模型上下文或 checkpoint。

没有可用订单时，物流分支状态为 `SKIPPED_NO_MATCHING_ORDER`，最终回答明确说明没有找到可用于物流查询的订单，不得使用客户订单状态代替物流状态。

## 5. 工作流节点和状态

工作流按两波执行：

```text
input.validate
      |
      +--> customer-orders.query ----> resolve.latest-order ----> logistics.query
      |
      +--> knowledge.query
      |
      +--> result.validate --> answer.compose
```

建议在 `CompositeQueryState` 增加以下服务端字段：

```text
resolvedOrderCode       // 仅公开订单号，脱敏后可用于恢复
resolvedOrderSource     // customer-orders
resolvedOrderAt         // 选择依据时间
dependencyStatuses      // WAITING/RUNNABLE/SUCCESS/SKIPPED/FAILED
publishedResultKinds    // 防止恢复时重复发布
```

每个节点完成后保存 checkpoint。依赖节点只有在前置结果为 `SUCCESS` 且 `resolvedOrderCode` 非空时才变为 `RUNNABLE`。知识库查询不依赖业务查询，可与基础分支并行。

## 6. 部分成功和失败恢复

工作流状态增加 `PARTIAL_SUCCESS`。结果门禁按以下规则处理：

- 客户订单成功、物流失败：发布客户订单卡片，明确“物流查询失败/暂不可用”，不得输出物流状态、轨迹时间或停滞阈值判断；
- 客户订单成功、物流无匹配订单：发布订单事实，说明没有可查询物流的订单；
- 客户订单失败：物流依赖分支不执行，返回查询失败原因的安全摘要；
- 知识库失败：若规则判断是必需项，禁止生成规则结论；仅业务事实仍可展示；
- 售后规则知识成功但未查询售后工单：只能说明规则和缺少工单事实，不得声称工单不存在；
- 已成功且已发布的分支在恢复时直接复用 checkpoint，不重复调用或重复发布；
- checkpoint 缺失、版本不匹配或 Redis 不可用：安全终止并提示重试，不伪造恢复成功。

最终回答必须区分“已取得的业务事实”“已核验的知识依据”“失败/缺失分支”和“当前不能确认的结论”。

## 7. Checkpoint 设计

继续使用现有 Redis checkpoint 适配器和独立 key 前缀。除现有字段外保存：

```text
resolvedOrderCode
resolvedOrderAt
dependencyStatuses
publishedResultKinds
```

保存内容不得包含 Token、完整异常堆栈、内部主键、完整手机号、详细地址或未经脱敏的下游原文。依赖分支幂等键为：

```text
requestId + nodeId + intentHash + resolvedOrderCode
```

恢复流程：读取最新 checkpoint -> 校验 graphVersion/planHash -> 恢复已完成节点和 `resolvedOrderCode` -> 仅执行未完成的依赖分支 -> 重新进行结果门禁和回答编排。

## 8. 观测与审计

在现有复合查询指标上增加低基数标签/计数：

- `dependency_type=LATEST_ORDER`；
- `dependency_wait_total`、`dependency_resolve_total`；
- `partial_success_total`；
- `checkpoint_resume_total{result=success|failed|expired}`；
- 每个分支的成功、跳过、失败和耗时。

所有日志继续使用同一个 `requestId`/`traceId`，只记录公开订单号或哈希后的标识，不记录敏感字段。

## 9. 测试与验收

### 计划测试

- 客户 + 最近订单 + 物流 + 售后规则生成依赖计划；
- 只有售后规则时不生成售后工单查询；
- 订单号缺失时不把客户号传给物流；
- 普通客户订单查询仍保持现有计划。

### 工作流测试

- 客户订单成功后按时间选择最近订单，并将公开订单号传给物流 Gateway；
- 物流分支必须等待最近订单解析；
- 客户订单成功、物流失败时返回 `PARTIAL_SUCCESS`，不出现物流推断；
- 知识库成功但售后工单未查询时不出现“未查询到工单”断言；
- 无订单时物流分支安全跳过；
- 分支结果发布顺序确定。

### Checkpoint 测试

- 在最近订单解析后中断，恢复时不重复客户订单查询；
- 在物流成功后中断，恢复时不重复物流查询或卡片发布；
- checkpoint 版本不匹配、Redis 读取失败和 TTL 过期均安全失败。

### 端到端验收问题

```text
查询客户 C24101816040001 最近一笔订单的物流状态，并结合售后规则判断。
```

期望回答至少包含：客户最近订单的已验证事实、真实物流查询结果或明确失败信息、售后规则依据、缺失事实和不能确认的结论；不得把“在途”订单状态直接当成物流状态，也不得声称未查询的售后工单不存在。

## 10. 非目标

- 不改变单一订单、单一物流、单一客户和单一商品查询路径；
- 不把外部市场数据接入本图；
- 不新增退款、退货、预警生成、联系承运商等写操作；
- 不让模型自行决定依赖节点或访问未白名单 Gateway。
