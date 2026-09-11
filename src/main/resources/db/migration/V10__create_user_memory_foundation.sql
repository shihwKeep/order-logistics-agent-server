CREATE TABLE agent_user_memory_setting (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '数据库主键',
    tenant_id BIGINT NOT NULL COMMENT '所属租户，来自认证身份',
    user_id BIGINT NOT NULL COMMENT '所属用户，来自认证身份',
    memory_generation BIGINT NOT NULL DEFAULT 1 COMMENT '当前用户记忆世代',
    auto_extract_enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否允许隐式自动抽取',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    updated_at DATETIME(3) NOT NULL COMMENT '更新时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_memory_setting_owner (tenant_id, user_id)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '用户记忆设置与世代';

CREATE TABLE agent_user_memory (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '数据库主键',
    memory_id CHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '对外记忆UUID',
    tenant_id BIGINT NOT NULL COMMENT '所属租户，来自认证身份',
    user_id BIGINT NOT NULL COMMENT '所属用户，来自认证身份',
    memory_generation BIGINT NOT NULL COMMENT '所属用户记忆世代',
    source_type VARCHAR(24)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'USER_EXPLICIT或AUTO_EXTRACT',
    category VARCHAR(48)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '记忆类别',
    canonical_key VARCHAR(128)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定语义键',
    content VARCHAR(512) NOT NULL COMMENT '经验证的安全记忆文本',
    content_hash CHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范化内容SHA-256',
    confidence DECIMAL(5,4) NOT NULL COMMENT '抽取置信度',
    visibility VARCHAR(16)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'VISIBLE或HIDDEN',
    retention_type VARCHAR(16)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'NORMAL或PERMANENT',
    status VARCHAR(16)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'ACTIVE、SUPERSEDED、DELETED或EXPIRED',
    source_conversation_id CHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '来源会话',
    source_message_sequence BIGINT NOT NULL COMMENT '来源用户消息序号',
    evidence_text VARCHAR(512) NOT NULL COMMENT '受限用户原文证据',
    version BIGINT NOT NULL COMMENT '同语义键版本，从1开始',
    expires_at DATETIME(3) NULL COMMENT '普通记忆过期时间，UTC',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    updated_at DATETIME(3) NOT NULL COMMENT '更新时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_memory_id (memory_id),
    KEY idx_memory_owner_status
        (tenant_id, user_id, memory_generation, status, updated_at, id),
    KEY idx_memory_owner_key
        (tenant_id, user_id, memory_generation, canonical_key, status),
    KEY idx_memory_expiry (status, expires_at, id),
    CONSTRAINT fk_memory_conversation
        FOREIGN KEY (source_conversation_id)
        REFERENCES agent_conversation (conversation_id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT chk_memory_confidence
        CHECK (confidence >= 0.0000 AND confidence <= 1.0000),
    CONSTRAINT chk_memory_version CHECK (version >= 1)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '跨会话用户记忆权威表';

CREATE TABLE agent_memory_suppression (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '数据库主键',
    suppression_id CHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '抑制记录UUID',
    tenant_id BIGINT NOT NULL COMMENT '所属租户',
    user_id BIGINT NOT NULL COMMENT '所属用户',
    memory_generation BIGINT NOT NULL COMMENT '所属用户记忆世代',
    canonical_key VARCHAR(128)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '被抑制语义键',
    content_hash CHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '可选内容指纹',
    status VARCHAR(16)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'ACTIVE或LIFTED',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    updated_at DATETIME(3) NOT NULL COMMENT '更新时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_memory_suppression_id (suppression_id),
    KEY idx_suppression_owner_key
        (tenant_id, user_id, memory_generation, canonical_key, status)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '用户记忆删除抑制';

CREATE TABLE agent_memory_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '数据库主键',
    event_id CHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '索引事件UUID',
    memory_id CHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '单条事件记忆UUID',
    tenant_id BIGINT NOT NULL COMMENT '所属租户',
    user_id BIGINT NOT NULL COMMENT '所属用户',
    memory_generation BIGINT NOT NULL COMMENT '事件对应记忆世代',
    memory_version BIGINT NOT NULL COMMENT '记忆版本，范围事件为0',
    operation VARCHAR(24)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '索引操作',
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
    UNIQUE KEY uk_memory_outbox_event (event_id),
    KEY idx_memory_outbox_claim (status, next_run_at, id),
    KEY idx_memory_outbox_owner
        (tenant_id, user_id, memory_generation, id),
    CONSTRAINT chk_memory_outbox_retry CHECK (retry_count >= 0),
    CONSTRAINT chk_memory_outbox_version CHECK (memory_version >= 0)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '用户记忆索引Outbox';
