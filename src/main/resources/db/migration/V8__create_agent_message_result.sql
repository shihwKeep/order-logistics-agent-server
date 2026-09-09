CREATE TABLE agent_message_result (
                                      id BIGINT NOT NULL AUTO_INCREMENT COMMENT '数据库主键',

                                      tenant_id BIGINT NOT NULL COMMENT '租户ID',
                                      user_id BIGINT NOT NULL COMMENT '坐席用户ID',

                                      conversation_id CHAR(36)
                                             CHARACTER SET ascii COLLATE ascii_bin
                                          NOT NULL COMMENT '会话ID',

                                      request_id CHAR(36)
                                             CHARACTER SET ascii COLLATE ascii_bin
                                          NOT NULL COMMENT '本轮请求ID',

                                      message_id CHAR(36)
                                             CHARACTER SET ascii COLLATE ascii_bin
                                          NOT NULL COMMENT '所属助手消息ID',

                                      result_sequence INT NOT NULL
                                          COMMENT '同一回答内结构化结果序号，从1开始',

                                      tool_name VARCHAR(64)
                                             CHARACTER SET ascii COLLATE ascii_bin
                                          NOT NULL COMMENT '产生结果的工具名称',

                                      kind VARCHAR(32)
                                             CHARACTER SET ascii COLLATE ascii_bin
                                          NOT NULL COMMENT '结构化结果类型',

                                      schema_version INT NOT NULL
                                          COMMENT '结构化结果协议版本',

                                      payload_json MEDIUMTEXT NOT NULL
                                          COMMENT '经过裁剪和脱敏的业务结果JSON快照',

                                      queried_at DATETIME(3) NOT NULL
        COMMENT '业务数据查询时间，按UTC保存',

                                      created_at DATETIME(3) NOT NULL
        COMMENT '创建时间，由应用按UTC写入',

                                      PRIMARY KEY (id),

                                      UNIQUE KEY uk_message_result_request_sequence (
                                          request_id,
                                          result_sequence
                                          ),

                                      KEY idx_message_result_owner_message (
        tenant_id,
        user_id,
        conversation_id,
        message_id
    ),

                                      CONSTRAINT fk_message_result_conversation
                                          FOREIGN KEY (conversation_id)
                                              REFERENCES agent_conversation (conversation_id)
                                              ON DELETE RESTRICT
                                              ON UPDATE RESTRICT,

                                      CONSTRAINT fk_message_result_message
                                          FOREIGN KEY (message_id)
                                              REFERENCES agent_message (message_id)
                                              ON DELETE RESTRICT
                                              ON UPDATE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Agent助手消息结构化结果快照';