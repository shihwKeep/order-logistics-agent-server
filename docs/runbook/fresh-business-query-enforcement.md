# 实时业务查询本轮结果约束验证手册

## 1. Nacos 开关

在 Agent 服务对应的 Nacos 配置中增加：

```properties
# 实时业务查询必须产生本轮新鲜结构化结果；生产环境保持开启
agent.chat.business-query-enforcement.enabled=true
```

该配置只放在 Nacos，不要复制到仓库的 `application.properties` 或 YAML。修改后重启 Agent 服务，使新配置和新代码同时生效。

紧急回退时可在 Nacos 将其临时改为 `false`。关闭后，手动输入的业务问题恢复原有模型执行方式，不再执行本轮结构化结果约束。

## 2. 手工验证顺序

在同一个新会话中依次发送：

```text
查看售后工单 HH20260414_00002
查看售后工单 HH20260414_00002
查看订单 XJTS0120260820000011 的物流
帮忙查下客户 C24101816040001 的订单
查询鱼油商品
你好，请介绍一下你自己
```

预期行为：

- 两次完全相同的售后查询都重新调用售后服务，并各自产生一张 `after-sale-detail` 卡片。
- 完整订单号的物流查询直接调用物流查询动作，产生 `logistics-timeline` 卡片。
- 完整客户编号的订单查询直接调用客户订单动作，产生 `order-list` 卡片。
- “查询鱼油商品”仍由模型补全工具选择；只有本轮产生 `product-list` 结果后，卡片与正文才会发给前端。
- 普通自我介绍仍保持原有流式回答，不要求业务卡片。
- 如果模型业务查询本轮未真正产生允许类型的结构化结果，前端只显示固定提示“本轮未完成实时业务查询，请补充查询条件或稍后重试。”，不能显示模型生成的“已查询”“卡片已展示”等成功话术。

## 3. 数据库只读核验

将 `:conversation_id` 替换为本次验证会话的真实 ID，执行：

```sql
SELECT m.message_sequence,
       m.role,
       m.status,
       GROUP_CONCAT(r.kind ORDER BY r.result_sequence) AS result_kinds
FROM agent_message m
LEFT JOIN agent_message_result r ON r.message_id = m.message_id
WHERE m.conversation_id = :conversation_id
GROUP BY m.message_id, m.message_sequence, m.role, m.status
ORDER BY m.message_sequence DESC
LIMIT 8;
```

核心不变量：两次重复售后查询对应的每一条成功助手消息，都有自己本轮生成的 `after-sale-detail`；任何声称卡片查询成功的助手消息都不能出现 `result_kinds = NULL`。

## 4. 日志核验

自然语言业务查询走模型时，检索日志 `chat_business_query_gate`：

- `outcome=FRESH_RESULT_ACCEPTED`：本轮产生了允许类型的结构化结果，卡片和回答已统一发布。
- `outcome=MISSING_RESULT`：本轮没有工具结果，模型成功话术已被固定提示替换。
- `outcome=UNEXPECTED_RESULT`：本轮产生的结果类型与当前问题不匹配，整批结果和模型话术均未发布。

日志只记录请求 ID、计划模式、结果类型和判定，不记录用户原文或业务编号。
