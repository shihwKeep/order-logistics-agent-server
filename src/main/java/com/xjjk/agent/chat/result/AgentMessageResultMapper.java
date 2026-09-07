package com.xjjk.agent.chat.result;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 结构化消息结果的数据访问接口。 */
@Mapper
public interface AgentMessageResultMapper extends BaseMapper<AgentMessageResultEntity> {

    /**
     * 在聊天收尾事务中批量写入同一助手消息产生的结构化结果。
     * 列表不能为空，归属字段必须由可信的 ChatTurnContext 生成。
     */
    @Insert("""
        <script>
        INSERT INTO agent_message_result (
            tenant_id, user_id, conversation_id, request_id, message_id,
            result_sequence, tool_name, kind, schema_version, payload_json,
            queried_at, created_at
        ) VALUES
        <foreach collection="entities" item="entity" separator=",">
            (#{entity.tenantId}, #{entity.userId}, #{entity.conversationId},
             #{entity.requestId}, #{entity.messageId}, #{entity.resultSequence},
             #{entity.toolName}, #{entity.kind}, #{entity.schemaVersion},
             #{entity.payloadJson}, #{entity.queriedAt}, #{entity.createdAt})
        </foreach>
        </script>
        """)
    int insertBatch(@Param("entities") List<AgentMessageResultEntity> entities);

    /**
     * 一次读取当前历史消息页的全部结构化结果，避免逐条消息查询造成 N+1。
     */
    @Select("""
        <script>
        SELECT id,
               tenant_id AS tenantId,
               user_id AS userId,
               conversation_id AS conversationId,
               request_id AS requestId,
               message_id AS messageId,
               result_sequence AS resultSequence,
               tool_name AS toolName,
               kind,
               schema_version AS schemaVersion,
               payload_json AS payloadJson,
               queried_at AS queriedAt,
               created_at AS createdAt
        FROM agent_message_result
        WHERE tenant_id = #{tenantId}
          AND user_id = #{userId}
          AND conversation_id = #{conversationId}
          AND message_id IN
          <foreach collection="messageIds" item="messageId"
                   open="(" separator="," close=")">
              #{messageId}
          </foreach>
        ORDER BY message_id ASC, result_sequence ASC
        </script>
        """)
    List<AgentMessageResultEntity> selectByMessageIds(
            @Param("tenantId") long tenantId,
            @Param("userId") long userId,
            @Param("conversationId") String conversationId,
            @Param("messageIds") List<String> messageIds);
}
