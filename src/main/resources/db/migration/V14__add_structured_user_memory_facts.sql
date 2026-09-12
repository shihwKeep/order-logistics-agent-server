ALTER TABLE agent_user_memory
    ADD COLUMN schema_version SMALLINT UNSIGNED NULL
        COMMENT '结构化记忆模式版本' AFTER category,
    ADD COLUMN memory_type VARCHAR(40)
        CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT '通用语义记忆类型' AFTER schema_version,
    ADD COLUMN predicate_name VARCHAR(96)
        CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT '服务端规范事实谓词' AFTER memory_type,
    ADD COLUMN value_json JSON NULL
        COMMENT '规范化事实值' AFTER predicate_name,
    ADD COLUMN stability VARCHAR(16)
        CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT '事实稳定性' AFTER value_json,
    ADD COLUMN verification_method VARCHAR(32)
        CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT '事实核验方式' AFTER stability,
    ADD KEY idx_memory_owner_predicate (
        tenant_id, user_id, memory_generation, predicate_name, status, updated_at, id
    ),
    ADD CONSTRAINT chk_user_memory_structured_fact CHECK (
        (schema_version IS NULL
            AND memory_type IS NULL
            AND predicate_name IS NULL
            AND value_json IS NULL
            AND stability IS NULL
            AND verification_method IS NULL)
        OR
        (schema_version = 2
            AND memory_type IN (
                'PROFILE', 'COMMUNICATION_PREFERENCE', 'RESPONSE_PREFERENCE',
                'WORK_CONTEXT', 'STABLE_PREFERENCE', 'STABLE_USER_FACT'
            )
            AND predicate_name IS NOT NULL
            AND value_json IS NOT NULL
            AND stability = 'STABLE'
            AND verification_method IN (
                'DETERMINISTIC', 'SEMANTIC_MODEL',
                'EXPLICIT_DETERMINISTIC', 'EXPLICIT_SEMANTIC'
            ))
    );

ALTER TABLE agent_memory_extraction_task
    DROP CHECK chk_memory_extraction_result_code,
    ADD CONSTRAINT chk_memory_extraction_result_code CHECK (
        result_code IS NULL OR result_code IN (
            'SAVED', 'IGNORE', 'SESSION_ONLY', 'MODEL_EMPTY', 'ALL_REJECTED',
            'REJECTED_SCHEMA', 'REJECTED_EVIDENCE', 'REJECTED_SENSITIVE',
            'REJECTED_STABILITY', 'REJECTED_CONFIDENCE', 'REJECTED_UNSUPPORTED',
            'REJECTED_CONTRADICTED', 'REJECTED_UNCERTAIN',
            'NO_CHANGE', 'MODEL_PROTOCOL_REJECTED'
        )
    );
