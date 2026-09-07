ALTER TABLE agent_conversation
    ADD COLUMN memory_version BIGINT NOT NULL DEFAULT 0
    COMMENT '已结束历史窗口版本；开始请求时不变，收尾或恢复成功时递增',
    ADD COLUMN memory_until_sequence BIGINT NOT NULL DEFAULT 0
        COMMENT '已结束历史的消息序号边界；不代表该边界内所有消息都可进入模型';

UPDATE agent_conversation AS conversation
SET conversation.memory_until_sequence = COALESCE(
        (
            SELECT MAX(assistant_message.message_sequence)
            FROM agent_message AS assistant_message
            WHERE assistant_message.tenant_id =
                  conversation.tenant_id
              AND assistant_message.user_id =
                  conversation.user_id
              AND assistant_message.conversation_id =
                  conversation.conversation_id
              AND assistant_message.role = 'ASSISTANT'
              AND assistant_message.status <> 'GENERATING'
        ),
        0
                                         ),
    conversation.memory_version =
        conversation.history_version;