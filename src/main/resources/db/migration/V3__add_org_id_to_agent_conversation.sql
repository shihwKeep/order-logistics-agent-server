ALTER TABLE agent_conversation
    ADD COLUMN org_id BIGINT NULL
        COMMENT '创建会话时的组织ID快照，来自认证身份；历史未知时为空'
        AFTER user_id;