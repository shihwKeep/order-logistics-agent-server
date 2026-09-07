package com.xjjk.agent.chat.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xjjk.agent.chat.persistence.entity.AgentConversationEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Agent 会话数据访问接口。
 *
 * 提供会话表的基础增删改查能力。
 * 访问已有会话时，业务层必须附带租户和用户归属条件。
 */
@Mapper
public interface AgentConversationMapper
        extends BaseMapper<AgentConversationEntity> {

    /**
     * 有界查找稳定历史领先于任务目标，或尚未建立任务行的会话。
     * 补偿任务只登记游标，不在查询事务内生成摘要。
     */
    @Select("""
        SELECT c.*
        FROM agent_conversation c
        LEFT JOIN agent_summary_task t
          ON t.conversation_id = c.conversation_id
         AND t.tenant_id = c.tenant_id
         AND t.user_id = c.user_id
        WHERE c.memory_version > 0
          AND c.memory_until_sequence > 0
          AND (
                t.id IS NULL
                OR c.memory_version > t.requested_memory_version
                OR (
                    t.status = 'IDLE'
                    AND c.memory_version > t.last_evaluated_memory_version
                )
          )
        ORDER BY c.updated_at, c.id
        LIMIT #{limit}
        """)
    List<AgentConversationEntity> selectSummaryRepairCandidates(
            @Param("limit") int limit
    );
}
