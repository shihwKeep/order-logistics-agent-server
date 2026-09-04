package com.xjjk.agent.chat.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xjjk.agent.chat.persistence.entity.AgentMessageEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * Agent 消息数据访问接口。
 *
 * 提供消息表的基础增删改查能力。
 * 查询消息时，业务层必须限定租户、用户和所属会话。
 */
@Mapper
public interface AgentMessageMapper
        extends BaseMapper<AgentMessageEntity> {
}