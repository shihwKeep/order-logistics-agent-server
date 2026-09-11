ALTER TABLE agent_user_memory_setting
    ADD COLUMN memory_enabled TINYINT(1) NOT NULL DEFAULT 1
        COMMENT '用户级长期记忆总开关'
        AFTER memory_generation;
