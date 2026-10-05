# 离线确定性黄金评测集设计

## 1. 目标

建立一套不依赖真实模型、数据库、Redis、知识库或外部 HTTP 服务的离线评测，固定验证 Agent 的业务编排、工具选择、结果门禁、空结果短路、并行执行和 checkpoint 恢复行为。

评测结果应能在本地和 CI 中重复执行，能够明确指出失败的 `caseId`、失败断言和实际调用分支；本阶段不引入独立评测服务，也不改变生产运行时逻辑。

## 2. 范围与非目标

### 范围

- 订单、物流、客户订单、商品和知识库复合查询的确定性行为。
- 不存在业务对象、业务空结果和下游不可用时的安全结果。
- 知识库是否被调用及其调用顺序。
- 同参工具复用、不同分支并行和 checkpoint 恢复。
- 结果状态、结果类型、敏感字段和内部标识泄露检查。

### 非目标

- 不评价模型自然语言质量、LLM Judge 分数或外部市场价格。
- 不连接本地业务服务、Nacos、MySQL、Redis、ES、Milvus 或真实模型。
- 不替代生产集成测试和线上观测验证。
- 不新增生产配置、数据库表或 API。

## 3. 评测数据模型

评测数据放在 `src/test/resources/evaluation/agent-golden-cases.json`，按版本维护。每个用例至少包含：

```json
{
  "caseId": "order-logistics-001",
  "version": "v1",
  "category": "BUSINESS_KNOWLEDGE_COMPOSITE",
  "input": "查询订单物流并结合停滞规则判断",
  "plan": ["ORDER_LOGISTICS", "KNOWLEDGE"],
  "fixtures": ["order.logistics.normal", "knowledge.logistics.policy"],
  "expect": {
    "status": "SUCCESS",
    "resultKinds": ["logistics-timeline", "knowledge-citations"],
    "knowledgeCalls": 1,
    "forbiddenCalls": [],
    "sensitiveValuesAbsent": true
  }
}
```

字段约束：

- `caseId` 在同一版本内唯一，只使用低基数、可读标识。
- `input` 只用于测试场景标识，不保存真实客户问题或敏感业务载荷。
- `fixtures` 引用测试代码中的固定构造器，不把完整业务对象重复写入 JSON。
- `expect.status` 使用现有业务状态枚举语义，不引入仅评测可见的状态。
- 评测报告只输出 caseId、断言名称和安全摘要，不输出 Token、完整客户编号、手机号、地址或内部 ID。

## 4. 第一版用例矩阵

| caseId | 场景 | 关键断言 |
|---|---|---|
| `order-logistics-001` | 正常订单物流查询 | 只调用物流分支，结果为 `SUCCESS` |
| `order-logistics-policy-001` | 物流 + 知识规则复合查询 | 两类结果均存在，知识库只调用一次 |
| `customer-order-aftersale-001` | 客户最近订单 + 售后规则 | 客户订单先完成，内部客户 ID 不进入结果 |
| `product-price-001` | 有效 SKU + 定价规则 | 商品结果存在，缺少订单级数据时不编造价格结论 |
| `order-not-found-001` | 不存在订单 | 状态为 `NOT_FOUND`，不调用知识库和物流 |
| `customer-not-found-001` | 不存在客户 | 状态为 `NOT_FOUND`，不查询订单、不调用知识库 |
| `sku-not-found-001` | 不存在 SKU | 状态为 `NOT_FOUND`，不调用知识库 |
| `empty-business-skip-kb-001` | 业务空结果后的规则问题 | 知识库调用次数为 0，返回业务事实不足提示 |
| `downstream-unavailable-001` | 下游服务不可用 | 状态为 `FAILED`，只返回安全消息，不泄露异常正文 |
| `tool-reuse-001` | 同参工具重复请求 | 保护器记录复用，真实 Gateway 调用不重复 |
| `parallel-branches-001` | 不同业务分支并行 | 两个分支均完成，结果不因执行顺序改变 |
| `checkpoint-resume-001` | 中断后 checkpoint 恢复 | 已完成分支不重复调用，恢复后只执行未完成分支 |

## 5. 执行结构

新增测试包 `com.xjjk.agent.evaluation`，包含以下测试专用组件：

- `GoldenEvaluationCase`：JSON 数据模型。
- `GoldenEvaluationCaseLoader`：读取并校验数据集版本、caseId 和必填字段。
- `GoldenFixtureFactory`：提供订单、物流、客户、商品、售后、知识库和失败结果的固定构造器。
- `OfflineGoldenEvaluationTest`：参数化执行器，按 caseId 运行工作流并完成断言。
- `EvaluationAssertions`：统一检查状态、结果类型、调用次数、安全字段和 checkpoint 语义。

测试通过 Mockito 注入现有 Gateway，通过固定 `Clock` 消除时间漂移；并行测试使用确定性的门闩或完成顺序控制，不依赖线程调度结果。

## 6. 结果与失败诊断

执行器必须：

1. 在每个 case 开始和结束时输出 `caseId`、版本和耗时。
2. 失败时输出第一条失败断言、期望值、实际值和安全摘要。
3. 任意 case 失败时让 Maven 命令失败。
4. 不把完整输入、工具参数、客户内部 ID 或异常堆栈正文写入评测报告。
5. 对用例顺序不敏感；每个 case 使用独立 mock、独立 checkpoint store 和独立 requestId。

## 7. 验收标准

- `mvn -Dtest=OfflineGoldenEvaluationTest test` 稳定通过。
- 重复执行至少三次，case 顺序和结果一致。
- 不存在订单、客户和 SKU 的用例均不会调用知识库。
- 复合查询用例验证并行分支、结果门禁和知识引用结果。
- checkpoint 恢复用例验证已完成分支不重复执行。
- 测试输出中不存在 Token、完整业务编号、客户内部 ID、手机号、地址或工具原始参数。
- 全量 `mvn test` 继续通过。

## 8. 后续扩展

本设计为后续独立评测服务保留版本字段和 caseId 语义。后续若建设评测控制面，可复用相同数据集和断言语义，但不应把离线测试直接改造成线上业务流量。
