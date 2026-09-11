package com.xjjk.agent.memory.service;

import com.xjjk.agent.memory.domain.ExplicitMemoryCandidate;

public interface ExplicitMemoryExtractor {
    ExplicitMemoryCandidate extract(
            ExplicitMemoryCommandDetector.CommandText command,
            String originalMessage);
}
