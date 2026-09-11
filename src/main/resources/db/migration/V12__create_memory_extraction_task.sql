CREATE TABLE agent_memory_extraction_task (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '数据库主键',
    task_id CHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '抽取任务UUID',
    tenant_id BIGINT NOT NULL COMMENT '所属租户',
    user_id BIGINT NOT NULL COMMENT '所属用户',
    conversation_id CHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '来源会话',
    request_id CHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '来源请求',
    user_message_id CHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '来源用户消息',
    user_message_sequence BIGINT NOT NULL COMMENT '来源用户消息序号',
    memory_generation BIGINT NOT NULL COMMENT '登记时用户记忆世代',
    status VARCHAR(16)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '任务状态',
    retry_count INT NOT NULL DEFAULT 0 COMMENT '连续重试次数',
    next_run_at DATETIME(3) NOT NULL COMMENT '最早执行时间，UTC',
    lease_token CHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '任务租约UUID',
    locked_by VARCHAR(128) NULL COMMENT '处理实例',
    locked_until DATETIME(3) NULL COMMENT '租约到期时间，UTC',
    last_error_code VARCHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '安全错误码',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    updated_at DATETIME(3) NOT NULL COMMENT '更新时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_memory_extraction_task_id (task_id),
    UNIQUE KEY uk_memory_extraction_request
        (tenant_id, user_id, conversation_id, request_id),
    KEY idx_memory_extraction_claim (status, next_run_at, id),
    KEY idx_memory_extraction_lease (status, locked_until, id),
    KEY idx_memory_extraction_owner
        (tenant_id, user_id, memory_generation, status, id),
    CONSTRAINT fk_memory_extraction_conversation
        FOREIGN KEY (conversation_id)
        REFERENCES agent_conversation (conversation_id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_memory_extraction_user_message
        FOREIGN KEY (user_message_id)
        REFERENCES agent_message (message_id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT chk_memory_extraction_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'RETRY', 'DONE', 'CANCELLED', 'DEAD')
    ),
    CONSTRAINT chk_memory_extraction_sequence CHECK (user_message_sequence >= 1),
    CONSTRAINT chk_memory_extraction_generation CHECK (memory_generation >= 1),
    CONSTRAINT chk_memory_extraction_retry CHECK (retry_count >= 0)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '隐式用户记忆抽取任务';
