ALTER TABLE agent_conversation
    MODIFY COLUMN title VARCHAR(128) NOT NULL DEFAULT '新会话'
        COMMENT '会话标题';

UPDATE agent_conversation
SET title = '新会话'
WHERE title = '新对话';

UPDATE agent_conversation AS conversation
JOIN (
    SELECT first_question.conversation_id,
           CASE
               WHEN CHAR_LENGTH(first_question.normalized_content) > 15
                   THEN CONCAT(LEFT(first_question.normalized_content, 15), '...')
               ELSE first_question.normalized_content
           END AS generated_title
    FROM (
        SELECT user_message.conversation_id,
               REGEXP_REPLACE(TRIM(user_message.content), '[[:space:]]+', ' ')
                   AS normalized_content
        FROM agent_message AS user_message
        WHERE user_message.role = 'USER'
          AND NOT EXISTS (
              SELECT 1
              FROM agent_message AS earlier_message
              WHERE earlier_message.conversation_id = user_message.conversation_id
                AND earlier_message.role = 'USER'
                AND earlier_message.message_sequence < user_message.message_sequence
          )
    ) AS first_question
    WHERE first_question.normalized_content <> ''
) AS title_source
    ON title_source.conversation_id = conversation.conversation_id
SET conversation.title = title_source.generated_title
WHERE conversation.title IN ('新对话', '新会话')
  AND EXISTS (
      SELECT 1
      FROM agent_message AS assistant_message
      WHERE assistant_message.conversation_id = conversation.conversation_id
        AND assistant_message.role = 'ASSISTANT'
        AND assistant_message.status = 'SUCCESS'
  );
