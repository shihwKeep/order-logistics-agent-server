CREATE TABLE agent_conversation_summary (
                                            id BIGINT NOT NULL AUTO_INCREMENT COMMENT '数据库主键',
                                            tenant_id BIGINT NOT NULL COMMENT '所属租户，来自认证身份',
                                            user_id BIGINT NOT NULL COMMENT '所属坐席用户，来自认证身份',
                                            conversation_id CHAR(36)
                                                   CHARACTER SET ascii
                                                COLLATE ascii_bin
                                                NOT NULL COMMENT '所属会话ID',
                                            summary_version BIGINT NOT NULL COMMENT '当前摘要版本，从1开始',
                                            covered_until_sequence BIGINT NOT NULL
                                                COMMENT '摘要连续处理到的消息序号',
                                            source_memory_version BIGINT NOT NULL
                                                COMMENT '生成本版摘要时捕获的稳定历史版本',
                                            schema_version INT NOT NULL COMMENT '摘要JSON结构版本',
                                            content_json JSON NOT NULL COMMENT '经过校验的结构化摘要',
                                            prompt_version VARCHAR(64) NOT NULL COMMENT '摘要提示词版本',
                                            model_name VARCHAR(128) NOT NULL COMMENT '摘要生成模型',
                                            input_tokens BIGINT NULL
        COMMENT '摘要模型输入Token，供应商未返回时为空',
                                            output_tokens BIGINT NULL
        COMMENT '摘要模型输出Token，供应商未返回时为空',
                                            created_at DATETIME(3) NOT NULL
        COMMENT '创建时间，由应用按UTC写入',
                                            updated_at DATETIME(3) NOT NULL
        COMMENT '更新时间，由应用按UTC写入',

                                            PRIMARY KEY (id),
                                            UNIQUE KEY uk_conversation_summary_conversation (conversation_id),
                                            KEY idx_conversation_summary_owner (
        tenant_id,
        user_id,
        conversation_id
    ),

                                            CONSTRAINT fk_summary_conversation
                                                FOREIGN KEY (conversation_id)
                                                    REFERENCES agent_conversation (conversation_id)
                                                    ON DELETE RESTRICT
                                                    ON UPDATE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Agent会话当前长期摘要';


CREATE TABLE agent_summary_task (
                                    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '数据库主键',
                                    task_id CHAR(36)
                                           CHARACTER SET ascii
                                        COLLATE ascii_bin
                                              NOT NULL COMMENT '摘要调度任务ID',
                                    tenant_id BIGINT NOT NULL COMMENT '所属租户，来自认证身份',
                                    user_id BIGINT NOT NULL COMMENT '所属坐席用户，来自认证身份',
                                    conversation_id CHAR(36)
                                           CHARACTER SET ascii
                                        COLLATE ascii_bin
                                        NOT NULL COMMENT '所属会话ID',
                                    requested_memory_version BIGINT NOT NULL
                                        COMMENT '最新待检查稳定历史版本',
                                    requested_until_sequence BIGINT NOT NULL
                                        COMMENT '目标版本对应的稳定消息边界',
                                    last_evaluated_memory_version BIGINT NOT NULL DEFAULT 0
                                        COMMENT '最后成功完成摘要必要性判断的稳定版本',
                                    force_generation TINYINT(1) NOT NULL DEFAULT 0
        COMMENT '是否绕过普通最小触发阈值',
                                    force_reason VARCHAR(32)
                                           CHARACTER SET ascii
                                        COLLATE ascii_bin
                                        NULL COMMENT '强制摘要原因',
                                    status VARCHAR(16)
                                           CHARACTER SET ascii
                                        COLLATE ascii_bin
                                        NOT NULL COMMENT 'IDLE、PENDING、PROCESSING、RETRY或DEAD',
                                    retry_count INT NOT NULL DEFAULT 0 COMMENT '当前连续失败次数',
                                    next_run_at DATETIME(3) NOT NULL
        COMMENT '最早可再次执行时间，按UTC保存',
                                    lease_token CHAR(36)
                                           CHARACTER SET ascii
                                        COLLATE ascii_bin
                                        NULL COMMENT '当前领取资格UUID',
                                    locked_by VARCHAR(128) NULL COMMENT '当前处理实例',
                                    locked_until DATETIME(3) NULL
        COMMENT '租约失效时间，按UTC保存',
                                    last_error_code VARCHAR(64)
                                           CHARACTER SET ascii
                                        COLLATE ascii_bin
                                        NULL COMMENT '最后一次安全错误码',
                                    created_at DATETIME(3) NOT NULL
        COMMENT '创建时间，由应用按UTC写入',
                                    updated_at DATETIME(3) NOT NULL
        COMMENT '更新时间，由应用按UTC写入',

                                    PRIMARY KEY (id),
                                    UNIQUE KEY uk_summary_task_id (task_id),
                                    UNIQUE KEY uk_summary_task_conversation (conversation_id),
                                    KEY idx_summary_task_owner (
        tenant_id,
        user_id,
        conversation_id
    ),
                                    KEY idx_summary_task_claim (
        status,
        next_run_at,
        id
    ),
                                    KEY idx_summary_task_lease (
        status,
        locked_until,
        id
    ),

                                    CONSTRAINT fk_summary_task_conversation
                                        FOREIGN KEY (conversation_id)
                                            REFERENCES agent_conversation (conversation_id)
                                            ON DELETE RESTRICT
                                            ON UPDATE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Agent会话长期摘要调度状态';