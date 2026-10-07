# LangGraph4j 编排代码中文导读注释设计

## 目标

在不改变运行逻辑、方法签名、数据结构和格式行为的前提下，为当前 LangGraph4j 复合查询主链补充章节式中文导读注释，使读者能够从计划、状态、图构建、节点执行、并行分支一路读到 checkpoint 持久化与恢复。

## 修改范围

仅修改以下六个生产代码文件中的注释：

- `CompositeQueryWorkflow.java`
- `CompositeQueryState.java`
- `CompositeQueryPlan.java`
- `CompositeQueryIntent.java`
- `LangGraph4jRedisCheckpointSaver.java`
- `RedisCompositeQueryCheckpointStore.java`

不修改测试、配置、依赖和其他业务代码。以上文件中已经存在的未提交代码变更必须保留，只在其上增量补充注释。

## 注释组织方式

### 工作流入口

在 `CompositeQueryWorkflow` 类和 `execute()` 方法说明完整调用链，并按以下顺序标注步骤：

1. 创建单轮运行上下文；
2. 按计划构建并编译状态图；
3. 创建可序列化初始状态；
4. 配置 `threadId` 和并行执行器；
5. 尝试加载并合并 checkpoint；
6. 调用 `graph.invoke()`；
7. 将最终状态和运行期对象转换为应用结果。

### 图结构与节点

在 `graph()` 中加入简洁的 ASCII 流程图，并解释：

- `START`、`END` 是虚拟节点；
- `node_async` 包装节点动作；
- `edge_async` 负责条件路由；
- 依赖业务事实的知识查询串行执行；
- 相互独立的业务和知识分支并行执行；
- 汇合后统一进入结果校验和回答上下文组装。

六个主节点分别说明输入、职责和输出状态，不对显而易见的单行语句重复解释。

### State、Plan 与 Intent

- `CompositeQueryState`：按身份与请求、节点状态、分支恢复、依赖解析、最终输出分组解释字段；说明节点返回的是状态增量，而非完整 State。
- `CompositeQueryPlan`：说明必需结果、全部结果、计划标识和知识业务门禁。
- `CompositeQueryIntent`：说明数据源、依赖模式、标识来源，以及公开业务标识与内部主键的安全边界。

### Checkpoint

- `LangGraph4jRedisCheckpointSaver`：说明 LangGraph4j `Checkpoint` 与项目 `CompositeQueryCheckpoint` 的双向转换、脱敏白名单、已完成节点和下一节点的恢复语义。
- `RedisCompositeQueryCheckpointStore`：说明 Redis Key 格式、图版本校验、TTL、损坏 JSON 的处理和 Redis 基础设施异常的处理差异。

## 注释风格约束

- 使用中文，必要的 LangGraph4j 类名和状态名保留英文。
- 优先解释“为什么”和“这一段在整条链路中的位置”，避免逐行翻译代码。
- 步骤编号只用于主要生命周期，避免重复编号造成阅读冲突。
- 不增加 TODO、TBD、临时说明或面试话术。
- 不在注释中承诺代码实际未实现的能力。

## 验证

注释完成后执行：

1. `git diff --check`，确认没有空白和补丁格式问题；
2. `mvn -DskipTests compile`，确认注释未破坏编译；
3. LangGraph4j 兼容性、工作流、State 和 checkpoint 相关定向测试；
4. 检查 diff，确认六个目标文件只有注释变化。

