# 隐式记忆抽取结果可观测性设计

## 背景

当前隐式记忆抽取任务只记录任务状态和失败错误码。模型返回空候选、模型响应格式错误、候选全部被校验拒绝、候选因显式记忆优先或抑制规则未落库，最终都可能表现为 `DONE` 且没有记忆，无法通过数据库定位具体环节。

本次只补充安全、低基数的任务结果信息，不修改记忆抽取范围、置信度阈值、提示词或用户界面。

## 方案比较

1. **任务表持久化结果码和计数（采用）**：能够按单个任务追溯，重启后仍可查询，也便于生产排障。代价是增加一次数据库迁移和少量任务更新字段。
2. **仅增加日志和 Micrometer 指标**：改动较小，但日志可能轮转，聚合指标无法定位某个具体任务。
3. **复用 `last_error_code`**：字段最少，但会把正常空结果与真正错误混为一谈，语义不清晰，不采用。

## 数据模型

通过新的 Flyway 迁移为 `agent_memory_extraction_task` 增加：

- `result_code VARCHAR(40) NULL`：安全、低基数的处理结果码。
- `model_candidate_count SMALLINT UNSIGNED NOT NULL DEFAULT 0`：模型返回的候选数量。
- `accepted_candidate_count SMALLINT UNSIGNED NOT NULL DEFAULT 0`：通过确定性校验的候选数量。
- `saved_memory_count SMALLINT UNSIGNED NOT NULL DEFAULT 0`：实际写入的隐式记忆数量。

这些字段只保存数量和枚举码，不保存用户正文、证据文本、模型输出或拒绝细节。

迁移同时增加结果码白名单约束和 `saved <= accepted <= model` 计数约束。历史任务保持 `result_code = NULL`，不根据缺失信息推测回填结果。

## 结果码

- `SAVED`：至少写入一条隐式记忆。
- `MODEL_EMPTY`：模型合法返回，但候选数组为空。
- `ALL_REJECTED`：模型返回了候选，但全部被确定性校验拒绝。
- `NO_CHANGE`：存在通过校验的候选，但因显式记忆优先、抑制规则等原因未写入。
- `MODEL_PROTOCOL_REJECTED`：模型响应为空、格式不合法或字段不满足协议；任务仍按非重试结果结束。

可重试模型调用失败仍使用既有 `RETRY/DEAD + last_error_code`，任务取消仍使用既有 `CANCELLED + last_error_code`。`result_code` 只描述已完成任务的业务结果，不替代错误码。

## 处理流程

1. 模型调用成功后记录候选总数。
2. 逐条执行现有安全校验，统计通过数量，不记录被拒绝正文。
3. 提交服务返回实际写入数量。
4. 根据三个数量确定 `SAVED`、`MODEL_EMPTY`、`ALL_REJECTED` 或 `NO_CHANGE`，与计数一起原子完成任务。
5. 模型协议错误以 `MODEL_PROTOCOL_REJECTED` 完成任务，计数均为零。

任务完成更新与记忆、Outbox 写入继续处于同一事务，避免出现“记忆已写入但任务计数未更新”的不一致。

## 测试

- Mapper/迁移契约测试覆盖新增字段和完成更新参数。
- Worker 单元测试覆盖五种结果码以及对应计数。
- MySQL 事务集成测试验证任务结果、隐式记忆和 Outbox 同时提交。
- 运行完整 Maven 测试套件，确认现有显式记忆、清空、抑制和过期行为不受影响。

## 验收方式

重启服务后发送普通陈述 `我平时主要做 Java 开发。`，等待任务完成，再查询最新任务：

- 若为 `SAVED`，应同时存在 `AUTO_EXTRACT/HIDDEN/ACTIVE` 记忆和 `UPSERT/PENDING` Outbox。
- 若未保存，`result_code` 和三个计数必须能明确区分模型空结果、协议问题、规则拒绝或优先级跳过。
