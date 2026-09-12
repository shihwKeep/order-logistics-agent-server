package com.xjjk.agent.memory.service;

import com.xjjk.agent.memory.domain.ExplicitMemoryResolution;

public interface ExplicitMemoryExtractor {
    ExplicitMemoryResolution resolve(String originalMessage);
}
