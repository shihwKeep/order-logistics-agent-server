package com.xjjk.agent.chat.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xjjk.agent.chat.persistence.entity.AgentConversationEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * Agent 会话数据访问接口。
 *
 * 提供会话表的基础增删改查能力。
 * 访问已有会话时，业务层必须附带租户和用户归属条件。
 */
@Mapper
public interface AgentConversationMapper
        extends BaseMapper<AgentConversationEntity> {
}
