CREATE TABLE agent_message (
                               id BIGINT NOT NULL AUTO_INCREMENT COMMENT '数据库主键',

                               message_id CHAR(36)
                                      CHARACTER SET ascii COLLATE ascii_bin
                                         NOT NULL COMMENT '对外消息ID，由后端生成UUID',

                               conversation_id CHAR(36)
                                      CHARACTER SET ascii COLLATE ascii_bin
                                         NOT NULL COMMENT '所属会话ID',

                               tenant_id BIGINT NOT NULL COMMENT '所属租户，来自认证身份',
                               user_id BIGINT NOT NULL COMMENT '所属用户，来自认证身份',

                               request_id CHAR(36)
                                      CHARACTER SET ascii COLLATE ascii_bin
                                   NOT NULL COMMENT '关联本轮请求，同一问答使用相同请求ID',

                               message_sequence BIGINT NOT NULL
                                   COMMENT '会话内消息序号，从1开始',

                               role VARCHAR(16)
                                      CHARACTER SET ascii COLLATE ascii_bin
                                   NOT NULL COMMENT '消息角色：USER或ASSISTANT',

                               content MEDIUMTEXT NOT NULL
                                   COMMENT '消息正文，生成中的助手消息初始为空字符串',

                               status VARCHAR(32)
                                      CHARACTER SET ascii COLLATE ascii_bin
                                   NOT NULL COMMENT '消息状态，如GENERATING、SUCCESS、FAILED等',

                               finish_reason VARCHAR(64) NULL
        COMMENT '模型结束原因，如STOP、LENGTH；未知时为空',

                               error_code VARCHAR(64) NULL
        COMMENT '业务错误码，不保存供应商原始异常',

                               prompt_version VARCHAR(64) NULL
        COMMENT '助手消息使用的系统提示词版本，用户消息为空',

                               created_at DATETIME(3) NOT NULL
        COMMENT '创建时间，由应用按UTC写入',

                               updated_at DATETIME(3) NOT NULL
        COMMENT '更新时间，由应用按UTC写入',

                               PRIMARY KEY (id),

                               UNIQUE KEY uk_message_id (message_id),

                               UNIQUE KEY uk_conversation_sequence (
                                   conversation_id, message_sequence
                                   ),

                               UNIQUE KEY uk_request_role (
                                   request_id, role
                                   ),

                               KEY idx_message_owner_conversation (
        tenant_id, user_id, conversation_id, message_sequence
    ),

                               CONSTRAINT fk_message_conversation
                                   FOREIGN KEY (conversation_id)
                                       REFERENCES agent_conversation (conversation_id)
                                       ON DELETE RESTRICT
                                       ON UPDATE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Agent会话消息表';