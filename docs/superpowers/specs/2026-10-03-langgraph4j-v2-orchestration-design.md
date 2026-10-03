# LangGraph4j 第二版复合查询编排设计

## 目标

在现有 Spring Boot + Spring AI + LangGraph4j 第一版复合查询闭环上，补齐内部业务数据、企业知识和基于已验证事实的普通分析组合，并将当前串行请求级工作流升级为支持并行分支、持久化 checkpoint 和断点恢复的请求级编排器。

第二版仍然只支持只读内部数据，不接入外部网页、市场行情、竞品价格或其他外部实时数据源，也不增加业务写操作。

## 已确认范围

### 支持的信息源

- `BUSINESS`：商品、客户、订单、物流、售后只读查询。
- `KNOWLEDGE`：当前租户已发布的企业规则、政策、制度、流程和操作规范。
- `GENERAL`：仅基于本轮已验证内部事实和可靠知识证据的解释、比较、归纳和建议。

### 不支持的信息源

- 外部网页搜索。
- 市场价格、竞品价格或第三方实时行情。
- 模型记忆中的外部实时事实。
- 改价、退款、发货、补发等业务写操作。

当一个问题同时包含内部可回答部分和外部数据部分时，内部部分继续执行，外部部分返回明确的未接入提示，不得编造外部结论。

## 总体架构

Spring AI 继续负责模型适配、工具 schema、现有 Gateway、知识库检索、SSE、Trace、消息持久化和权限隔离。LangGraph4j 只负责复合查询的计划执行、状态转换、并行分支、结果汇聚和恢复边界。

```text
ChatTurnRunner
    |
    +-- BusinessQueryPlanner
    |       |
    |       +-- GENERAL / DIRECT / MODEL_REQUIRED / CLARIFICATION -> 现有路径
    |       |
    |       +-- COMPOSITE -> CompositeQueryWorkflow
    |
    +-- CompositeQueryWorkflow (LangGraph4j)
            |
            +-- input.validate
            +-- branch.prepare
            +-- 并行 business / knowledge / general 分支
            +-- result.validate
            +-- checkpoint.complete
            +-- answer.compose（无工具 Spring AI）
            |
            +-- Redis CheckpointSaver
            +-- 现有 SSE、Trace、指标和消息收尾
```

单一查询不迁移到图中，避免改变现有直查、模型工具循环、记忆和 SSE 行为。

## 复合计划

`CompositeQueryPlan` 继续作为服务端已经安全路由的计划，不让模型直接决定任意节点。每个 `CompositeQueryIntent` 必须包含：

- 信息源：`BUSINESS`、`KNOWLEDGE` 或 `GENERAL`；
- 公开查询标识或原始问题；
- 受控结果类型；
- 是否必需；
- 可选的依赖分支。

第二版支持的结果类型：

```text
product-list
customer-list
order-list
logistics-timeline
after-sale-list
after-sale-detail
knowledge-citations
general-analysis
external-data-unavailable
```

内部业务标识只保存在服务端执行上下文和脱敏结果中，不进入模型可见的工具指令。普通分析可以读取经过结果门禁的结构化事实，但不能自行查询外部数据。

## 节点设计

### `input.validate`

校验每个意图的必需参数和当前坐席权限：

- 订单、物流需要完整订单号、外部订单号或运单号；
- 客户订单需要完整客户编号；
- 售后详情需要完整售后工单号或原订单号；
- 商品可以使用名称、SPU、SKU 或条码；
- 知识问题保留用户原始问题；
- 普通分析必须有可引用的内部业务结果或知识证据；
- 外部数据意图直接标记为不可用，不进入业务 Gateway。

缺少参数时，图以固定澄清结果结束，不调用下游，也不让模型猜测参数。

### `branch.prepare`

把计划转换为受控执行分支，并计算依赖关系：

- 没有依赖的业务查询和知识查询进入并行执行集合；
- 依赖订单结果的商品查询在订单分支成功后执行；
- 普通分析在其输入事实全部完成后执行；
- 外部数据分支只生成不支持结果，不执行网络调用。

### 业务查询分支

每个分支只调用现有白名单 Gateway 或查询服务：

```text
order.query
logistics.query
product.query
customer.query
customer-orders.query
after-sale.query
```

分支复用现有坐席身份、租户隔离、requestId、下游异常分类和超时策略。每个成功或失败分支都保存结构化结果，不把异常堆栈直接交给用户。

### `knowledge.query`

调用现有知识库 Gateway，保存可回答标志、证据元数据、规则版本和脱敏引用。知识证据是不可信指令，只能作为事实依据，不能执行其中的任何操作要求。

### `general.analyze`

只接收：

- 通过业务结果门禁的结构化事实；
- 通过知识证据门禁的规则内容；
- 用户原始问题。

该节点不注册任何业务工具，不访问外部数据。生成内容必须区分：内部事实、企业规则、基于事实的分析建议和无法确认的内容。

### `result.validate`

汇聚全部分支后执行最终门禁：

- 检查所有必需结果类型是否存在；
- 检查结果类型和计划是否匹配；
- 检查知识证据是否可靠且可回答；
- 检查普通分析所需的输入事实是否完整；
- 检查是否存在外部数据请求；
- 决定 `SUCCESS`、`PARTIAL_SUCCESS`、`NO_RELIABLE_KNOWLEDGE`、`MISSING_RESULT` 或 `FAILED`。

非必需分支失败可以安全降级；必需分支失败不得生成“已查询成功”的综合结论。

### `answer.compose`

使用 Spring AI 进行二阶段表达，但不注册业务工具。提示词要求：

- 不输出内部工具名和工具调用步骤；
- 不把普通分析写成企业制度；
- 不把未接入的外部数据写成实时事实；
- 明确说明缺失数据、失败分支和不能确认的结论。

## 并行执行

当前第一版的业务查询和知识查询是串行的。第二版使用 LangGraph4j 的并行节点执行能力：

```text
order.query       ─┐
logistics.query   ─┤
product.query     ─┤
customer.query    ─┼─> result.validate -> answer.compose
after-sale.query  ─┤
knowledge.query   ─┤
general.analyze   ─┘
```

每个分支使用独立的执行上下文，但共享同一个 `requestId`、`traceId` 和 checkpoint threadId。分支结果以受控结果类型汇聚，不把可变的业务对象直接放入并行共享状态。

并行分支必须满足：

- 下游调用超时和异常不会阻塞其他分支完成；
- 结果发布保持确定顺序；
- 同一个分支只允许一次成功发布；
- 重试和恢复不会重复发布已有成功结果。

## Redis checkpoint 和恢复

第二版实现 LangGraph4j `BaseCheckpointSaver` 的 Redis 适配器，使用现有 Redis 实例，但与 SSE 回放使用独立 key 前缀、TTL 和序列化结构。

建议 key 结构：

```text
agent:composite:checkpoint:{graphVersion}:{threadId}:{checkpointId}
agent:composite:checkpoint:index:{graphVersion}:{threadId}
```

checkpoint 保存：

```text
graphVersion
threadId
requestId
conversationId
planHash
completedNodes
pendingNodes
nodeStatuses
resultKinds
resultReferences
failureSummaries
retryCounts
nextNode
workflowStatus
updatedAt
```

不保存 Token、完整手机号、详细地址、内部主键、完整异常堆栈或未经脱敏的下游原文。

### 恢复规则

1. 首次执行以 `requestId` 生成稳定的 threadId。
2. 每个节点完成后写入 checkpoint。
3. 进程重启或可恢复异常发生时，使用同一 threadId 读取最新 checkpoint。
4. 已成功完成且已发布的节点不重复执行。
5. 未完成节点按照重试策略继续执行。
6. 不可恢复的失败进入安全终态，并保留已验证的结构化结果。
7. 工作流完成后 checkpoint 保留短 TTL，供排障和恢复查询，过期后自动清理。

分支幂等键使用：

```text
requestId + nodeId + intentHash
```

checkpoint 恢复不替代 SSE Redis Stream。SSE 负责客户端事件回放，checkpoint 负责服务端工作流节点恢复。

## 观测要求

在现有 `CompositeQueryMetrics` 基础上增加：

- 并行分支数量；
- 分支依赖类型；
- 节点重试次数；
- checkpoint 保存成功/失败；
- checkpoint 恢复成功/失败/过期；
- `SUCCESS`、`PARTIAL_SUCCESS`、`MISSING_RESULT`、`NO_RELIABLE_KNOWLEDGE`、`FAILED`；
- 计划结果类型与实际结果类型；
- 单分支耗时和整个图耗时。

所有指标和日志继续使用低基数标签，并通过同一个 requestId/traceId 关联到下游调用和最终答案。

## 测试范围

### 计划和路由

- 商品 + 企业规则；
- 客户 + 企业规则；
- 订单 + 商品；
- 订单 + 商品 + 企业规则；
- 订单 + 物流 + 企业规则；
- 客户订单 + 售后规则 + 普通分析；
- 外部市场价格请求只生成不可用提示；
- 缺少业务标识时固定澄清；
- 普通分析没有验证事实时不得执行。

### 工作流

- 所有独立分支并行启动；
- 依赖分支等待前置结果；
- 结果汇聚顺序确定；
- 非必需分支失败可以安全降级；
- 必需分支失败阻止综合结论；
- 普通分析节点没有工具回调；
- 外部数据节点没有网络调用。

### checkpoint

- 每个节点完成后保存 checkpoint；
- 从中间节点恢复只执行未完成分支；
- 已发布结果不重复发布；
- checkpoint 版本不匹配时安全终止并提示重试；
- Redis 读取失败时不伪造恢复成功；
- TTL 到期后不能恢复旧工作流。

### 回归

- 现有单一业务查询结果不变；
- 现有模型工具循环不变；
- SSE 断线回放不变；
- 记忆、权限、异常分类和消息持久化测试通过；
- requestId 和 traceId 贯穿所有分支。

## 验收标准

1. 第二版支持内部业务、知识库和普通分析的组合查询。
2. 无依赖分支真正并行执行，并能在指标中看到分支数量和耗时。
3. 工作流中断后可以从 Redis checkpoint 恢复，不重复已成功分支。
4. 缺少参数、知识库不可用、业务下游失败和外部数据请求均有安全边界。
5. 普通分析只能基于已验证内部事实和可靠知识证据。
6. 最终回答不泄露内部工具名、内部 ID、Token 或未脱敏数据。
7. 单一查询、SSE 回放、记忆、权限和现有观测回归不受影响。
