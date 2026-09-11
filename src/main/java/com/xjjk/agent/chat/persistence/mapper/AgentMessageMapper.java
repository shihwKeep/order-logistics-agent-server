package com.xjjk.agent.chat.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xjjk.agent.chat.persistence.entity.AgentMessageEntity;
import com.xjjk.agent.chat.persistence.projection.ChatHistoryMessageMetadata;
import com.xjjk.agent.chat.persistence.projection.ChatSummaryMessageMetadata;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Agent 消息数据访问接口。
 *
 * 提供消息表的基础增删改查能力。
 * 查询消息时，业务层必须限定租户、用户和所属会话。
 */
@Mapper
public interface AgentMessageMapper
        extends BaseMapper<AgentMessageEntity> {

    /**
     * 读取指定会话最早的用户问题，用于首次生成稳定会话标题。
     */
    @Select("""
        SELECT content
        FROM agent_message
        WHERE tenant_id = #{tenantId}
          AND user_id = #{userId}
          AND conversation_id = #{conversationId}
          AND role = 'USER'
        ORDER BY message_sequence ASC
        LIMIT 1
        """)
    String selectEarliestUserContent(
            @Param("tenantId") long tenantId,
            @Param("userId") long userId,
            @Param("conversationId") String conversationId
    );

    /**
     * 倒序读取指定历史边界之前的消息元信息。
     *
     * 不过滤消息状态，避免隐藏失败轮次和破坏读取范围判断。
     * 归属、请求资格和参数范围由历史加载服务校验。
     *
     * @param tenantId 后端认证得到的租户 ID
     * @param userId 后端认证得到的用户 ID
     * @param conversationId 已校验归属的会话 ID
     * @param beforeSequence 排他上界，即本轮用户消息序号
     * @param limit 已校验的查询条数，包含用于探测更早消息的一条
     */
    @Select("""
        SELECT message_id AS messageId,
               request_id AS requestId,
               message_sequence AS messageSequence,
               role,
               status,
               OCTET_LENGTH(content) AS contentBytes
        FROM agent_message
        WHERE tenant_id = #{tenantId}
          AND user_id = #{userId}
          AND conversation_id = #{conversationId}
          AND message_sequence < #{beforeSequence}
        ORDER BY message_sequence DESC
        LIMIT #{limit}
        """)
    List<ChatHistoryMessageMetadata> selectHistoryMetadata(
            @Param("tenantId") long tenantId,
            @Param("userId") long userId,
            @Param("conversationId") String conversationId,
            @Param("beforeSequence") long beforeSequence,
            @Param("limit") int limit
    );

    /**
     * 正序读取长期摘要边界之后的候选消息元信息。
     * limit 由服务层多传一条，用于识别扫描上限，查询本身不读取正文。
     */
    @Select("""
        SELECT message_id AS messageId,
               request_id AS requestId,
               message_sequence AS messageSequence,
               role,
               status,
               OCTET_LENGTH(content) AS contentBytes
        FROM agent_message
        WHERE tenant_id = #{tenantId}
          AND user_id = #{userId}
          AND conversation_id = #{conversationId}
          AND message_sequence > #{afterSequence}
          AND message_sequence <= #{untilSequence}
        ORDER BY message_sequence ASC
        LIMIT #{limit}
        """)
    List<ChatSummaryMessageMetadata> selectSummaryMetadata(
            @Param("tenantId") long tenantId,
            @Param("userId") long userId,
            @Param("conversationId") String conversationId,
            @Param("afterSequence") long afterSequence,
            @Param("untilSequence") long untilSequence,
            @Param("limit") int limit
    );

    /**
     * 倒序读取稳定边界内最新的消息，用于按真实完整轮次保留近期原文。
     */
    @Select("""
        SELECT message_id AS messageId,
               request_id AS requestId,
               message_sequence AS messageSequence,
               role,
               status,
               OCTET_LENGTH(content) AS contentBytes
        FROM agent_message
        WHERE tenant_id = #{tenantId}
          AND user_id = #{userId}
          AND conversation_id = #{conversationId}
          AND message_sequence <= #{untilSequence}
        ORDER BY message_sequence DESC
        LIMIT #{limit}
        """)
    List<ChatSummaryMessageMetadata> selectRecentSummaryMetadata(
            @Param("tenantId") long tenantId,
            @Param("userId") long userId,
            @Param("conversationId") String conversationId,
            @Param("untilSequence") long untilSequence,
            @Param("limit") int limit
    );

    /**
     * 读取已经通过元信息筛选的连续消息正文范围。
     * 服务层会再次核对数量、ID、角色、状态和字节数，防止两阶段读取漂移。
     */
    @Select("""
        SELECT message_id AS messageId,
               request_id AS requestId,
               message_sequence AS messageSequence,
               role,
               status,
               content
        FROM agent_message
        WHERE tenant_id = #{tenantId}
          AND user_id = #{userId}
          AND conversation_id = #{conversationId}
          AND message_sequence >= #{fromSequence}
          AND message_sequence <= #{untilSequence}
        ORDER BY message_sequence ASC
        """)
    List<AgentMessageEntity> selectSummaryBodies(
            @Param("tenantId") long tenantId,
            @Param("userId") long userId,
            @Param("conversationId") String conversationId,
            @Param("fromSequence") long fromSequence,
            @Param("untilSequence") long untilSequence
    );
}
