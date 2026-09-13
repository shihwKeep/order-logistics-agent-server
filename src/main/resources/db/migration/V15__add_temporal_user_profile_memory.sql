ALTER TABLE agent_user_memory
    DROP CHECK chk_user_memory_structured_fact,
    ADD COLUMN observed_at DATETIME(3) NULL
        COMMENT '事实观测时间，UTC' AFTER stability,
    ADD COLUMN valid_from DATETIME(3) NULL
        COMMENT '事实生效时间，UTC' AFTER observed_at,
    ADD COLUMN valid_to DATETIME(3) NULL
        COMMENT '事实失效时间，UTC' AFTER valid_from,
    ADD COLUMN temporal_scope VARCHAR(16)
        CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT '事实时态范围' AFTER valid_to;

UPDATE agent_user_memory
SET schema_version = NULL,
    memory_type = NULL,
    predicate_name = NULL,
    value_json = NULL,
    stability = NULL,
    verification_method = NULL
WHERE schema_version = 2
  AND NOT (
      memory_type IS NOT NULL
      AND memory_type IN (
          'PROFILE', 'COMMUNICATION_PREFERENCE', 'RESPONSE_PREFERENCE',
          'WORK_CONTEXT', 'STABLE_PREFERENCE', 'STABLE_USER_FACT'
      )
      AND predicate_name IS NOT NULL
      AND value_json IS NOT NULL
      AND stability IS NOT NULL
      AND stability = 'STABLE'
      AND verification_method IS NOT NULL
      AND verification_method IN (
          'DETERMINISTIC', 'SEMANTIC_MODEL',
          'EXPLICIT_DETERMINISTIC', 'EXPLICIT_SEMANTIC'
      )
  );

UPDATE agent_user_memory
SET observed_at = created_at,
    valid_from = created_at,
    temporal_scope = 'CURRENT',
    schema_version = 3
WHERE schema_version = 2
  AND memory_type IS NOT NULL
  AND memory_type IN (
      'PROFILE', 'COMMUNICATION_PREFERENCE', 'RESPONSE_PREFERENCE',
      'WORK_CONTEXT', 'STABLE_PREFERENCE', 'STABLE_USER_FACT'
  )
  AND predicate_name IS NOT NULL
  AND value_json IS NOT NULL
  AND stability IS NOT NULL
  AND stability = 'STABLE'
  AND verification_method IS NOT NULL
  AND verification_method IN (
      'DETERMINISTIC', 'SEMANTIC_MODEL',
      'EXPLICIT_DETERMINISTIC', 'EXPLICIT_SEMANTIC'
  );

ALTER TABLE agent_user_memory
    ADD KEY idx_memory_owner_predicate_temporal_scope (
        tenant_id, user_id, memory_generation, predicate_name,
        temporal_scope, status, updated_at, id
    ),
    ADD CONSTRAINT chk_user_memory_structured_fact CHECK (
        (schema_version IS NULL
            AND memory_type IS NULL
            AND predicate_name IS NULL
            AND value_json IS NULL
            AND stability IS NULL
            AND verification_method IS NULL
            AND observed_at IS NULL
            AND valid_from IS NULL
            AND valid_to IS NULL
            AND temporal_scope IS NULL)
        OR
        (schema_version = 3
            AND memory_type IS NOT NULL
            AND memory_type IN (
                'PROFILE', 'COMMUNICATION_PREFERENCE', 'RESPONSE_PREFERENCE',
                'WORK_CONTEXT', 'STABLE_PREFERENCE', 'STABLE_USER_FACT'
            )
            AND predicate_name IS NOT NULL
            AND value_json IS NOT NULL
            AND stability IS NOT NULL
            AND stability IN ('STABLE', 'TIME_BOUND')
            AND verification_method IS NOT NULL
            AND verification_method IN (
                'DETERMINISTIC', 'SEMANTIC_MODEL',
                'EXPLICIT_DETERMINISTIC', 'EXPLICIT_SEMANTIC'
            )
            AND temporal_scope IS NOT NULL
            AND temporal_scope IN ('CURRENT', 'HISTORICAL')
            AND observed_at IS NOT NULL
            AND (temporal_scope = 'HISTORICAL' OR valid_from IS NOT NULL)
            AND (valid_to IS NULL OR (valid_from IS NOT NULL AND valid_to >= valid_from)))
    );
