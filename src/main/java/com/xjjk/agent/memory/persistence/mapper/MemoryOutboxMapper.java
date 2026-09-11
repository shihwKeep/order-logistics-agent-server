package com.xjjk.agent.memory.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xjjk.agent.memory.persistence.entity.MemoryOutboxEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MemoryOutboxMapper extends BaseMapper<MemoryOutboxEntity> {
}
