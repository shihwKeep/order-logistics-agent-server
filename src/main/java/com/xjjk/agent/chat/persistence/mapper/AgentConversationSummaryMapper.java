package com.xjjk.agent.chat.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xjjk.agent.chat.persistence.entity.AgentConversationSummaryEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 会话当前长期摘要数据访问接口。
 *
 * 所有读取和更新必须附带租户、用户及会话归属条件；
 * 后续摘要提交通过版本和覆盖边界比较更新，禁止旧任务覆盖新摘要。
 */
@Mapper
public interface AgentConversationSummaryMapper
        extends BaseMapper<AgentConversationSummaryEntity> {

    /** 按认证归属读取当前摘要，禁止仅按 conversationId 查询。 */
    @Select("""
        SELECT *
        FROM agent_conversation_summary
        WHERE tenant_id = #{tenantId}
          AND user_id = #{userId}
          AND conversation_id = #{conversationId}
        """)
    AgentConversationSummaryEntity selectOwned(
            @Param("tenantId") long tenantId,
            @Param("userId") long userId,
            @Param("conversationId") String conversationId
    );

    /** 只有摘要版本和连续覆盖边界都未变化时才允许更新。 */
    @Update("""
        UPDATE agent_conversation_summary
        SET summary_version = #{entity.summaryVersion},
            covered_until_sequence = #{entity.coveredUntilSequence},
            source_memory_version = #{entity.sourceMemoryVersion},
            schema_version = #{entity.schemaVersion},
            content_json = #{entity.contentJson},
            prompt_version = #{entity.promptVersion},
            model_name = #{entity.modelName},
            input_tokens = #{entity.inputTokens},
            output_tokens = #{entity.outputTokens},
            updated_at = #{entity.updatedAt}
        WHERE tenant_id = #{entity.tenantId}
          AND user_id = #{entity.userId}
          AND conversation_id = #{entity.conversationId}
          AND summary_version = #{expectedVersion}
          AND covered_until_sequence = #{expectedCoveredUntil}
        """)
    int updateCas(
            @Param("entity") AgentConversationSummaryEntity entity,
            @Param("expectedVersion") long expectedVersion,
            @Param("expectedCoveredUntil") long expectedCoveredUntil
    );
}
