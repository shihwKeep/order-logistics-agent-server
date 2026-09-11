ALTER TABLE agent_memory_extraction_task
    ADD COLUMN result_code VARCHAR(40)
        CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT '安全完成结果码' AFTER last_error_code,
    ADD COLUMN model_candidate_count SMALLINT UNSIGNED NOT NULL DEFAULT 0
        COMMENT '模型候选数量' AFTER result_code,
    ADD COLUMN accepted_candidate_count SMALLINT UNSIGNED NOT NULL DEFAULT 0
        COMMENT '校验通过候选数量' AFTER model_candidate_count,
    ADD COLUMN saved_memory_count SMALLINT UNSIGNED NOT NULL DEFAULT 0
        COMMENT '实际写入记忆数量' AFTER accepted_candidate_count,
    ADD CONSTRAINT chk_memory_extraction_result_code CHECK (
        result_code IS NULL OR result_code IN (
            'SAVED', 'MODEL_EMPTY', 'ALL_REJECTED', 'NO_CHANGE',
            'MODEL_PROTOCOL_REJECTED'
        )
    ),
    ADD CONSTRAINT chk_memory_extraction_result_counts CHECK (
        saved_memory_count <= accepted_candidate_count
        AND accepted_candidate_count <= model_candidate_count
    );
