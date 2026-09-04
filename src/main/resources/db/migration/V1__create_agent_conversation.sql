CREATE TABLE agent_conversation (
                                    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '数据库主键',

                                    conversation_id CHAR(36)
                                           CHARACTER SET ascii COLLATE ascii_bin
                                              NOT NULL COMMENT '对外会话ID，由后端生成UUID',

                                    tenant_id BIGINT NOT NULL COMMENT '所属租户，来自认证身份',
                                    user_id BIGINT NOT NULL COMMENT '所属用户，来自认证身份',

                                    title VARCHAR(128) NOT NULL DEFAULT '新对话'
                                        COMMENT '会话标题',

                                    last_message_sequence BIGINT NOT NULL DEFAULT 0
                                        COMMENT '已分配的最后一个消息序号',

                                    active_request_id CHAR(36)
                                           CHARACTER SET ascii COLLATE ascii_bin
                                        NULL COMMENT '当前占用会话的请求ID',

                                    active_until DATETIME(3) NULL
        COMMENT '当前请求占用的到期时间，按UTC保存',

                                    created_at DATETIME(3) NOT NULL
        COMMENT '创建时间，由应用按UTC写入',

                                    updated_at DATETIME(3) NOT NULL
        COMMENT '更新时间，由应用按UTC写入',

                                    PRIMARY KEY (id),

                                    UNIQUE KEY uk_conversation_id (conversation_id),

                                    KEY idx_conversation_owner_updated (
        tenant_id, user_id, updated_at, id
    )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Agent会话表';