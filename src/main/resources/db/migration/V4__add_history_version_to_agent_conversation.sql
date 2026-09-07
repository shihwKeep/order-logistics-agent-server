ALTER TABLE agent_conversation
    ADD COLUMN history_version BIGINT NOT NULL DEFAULT 0
    COMMENT '会话历史版本；消息新增或内容、状态变化时，在同一事务中递增';