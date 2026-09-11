package com.xjjk.agent.memory.service;

import com.xjjk.agent.memory.domain.ExplicitMemoryCandidate;
import com.xjjk.agent.memory.domain.MemoryCategory;
import com.xjjk.agent.memory.domain.MemoryRetentionType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 使用封闭语法把显式命令正文确定性映射为唯一记忆候选。
 */
public class DeterministicExplicitMemoryCandidateParser {

    private final MemoryCategoryContentPolicy contentPolicy;

    public DeterministicExplicitMemoryCandidateParser(MemoryCategoryContentPolicy contentPolicy) {
        this.contentPolicy = Objects.requireNonNull(contentPolicy, "contentPolicy");
    }

    public Optional<ExplicitMemoryCandidate> parse(
            ExplicitMemoryCommandDetector.CommandText command
    ) {
        Objects.requireNonNull(command, "command");
        List<ExplicitMemoryCandidate> matches = new ArrayList<>();
        for (MemoryCategory category : MemoryCategory.values()) {
            contentPolicy.canonicalize(category, command.payload())
                    .map(content -> new ExplicitMemoryCandidate(
                            category,
                            category.keyPrefix(),
                            content,
                            command.payload(),
                            command.permanent()
                                    ? MemoryRetentionType.PERMANENT
                                    : MemoryRetentionType.NORMAL
                    ))
                    .ifPresent(matches::add);
        }
        return matches.size() == 1
                ? Optional.of(matches.getFirst())
                : Optional.empty();
    }
}
