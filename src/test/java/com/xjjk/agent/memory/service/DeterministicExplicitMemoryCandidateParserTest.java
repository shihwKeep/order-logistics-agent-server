package com.xjjk.agent.memory.service;

import com.xjjk.agent.memory.domain.ExplicitMemoryCandidate;
import com.xjjk.agent.memory.domain.MemoryCategory;
import com.xjjk.agent.memory.domain.MemoryRetentionType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DeterministicExplicitMemoryCandidateParserTest {

    private final DeterministicExplicitMemoryCandidateParser parser =
            new DeterministicExplicitMemoryCandidateParser(new MemoryCategoryContentPolicy());

    @Test
    void parsesPermanentPreferredNameIntoCanonicalCandidate() {
        ExplicitMemoryCandidate candidate = parser.parse(
                new ExplicitMemoryCommandDetector.CommandText("叫我老师", true)
        ).orElseThrow();

        assertThat(candidate).isEqualTo(new ExplicitMemoryCandidate(
                MemoryCategory.PROFILE_PREFERRED_NAME,
                "profile.preferred_name",
                "用户希望被称为老师",
                "叫我老师",
                MemoryRetentionType.PERMANENT
        ));
    }

    @Test
    void parsesAllSupportedPreferredNames() {
        for (String name : List.of("老师", "先生", "女士", "同学", "伙伴", "朋友")) {
            ExplicitMemoryCandidate candidate = parser.parse(
                    new ExplicitMemoryCommandDetector.CommandText("叫我" + name, false)
            ).orElseThrow();

            assertThat(candidate.content()).isEqualTo("用户希望被称为" + name);
            assertThat(candidate.retentionType()).isEqualTo(MemoryRetentionType.NORMAL);
        }
    }

    @Test
    void parsesOtherClosedMemoryCategories() {
        assertThat(parser.parse(
                new ExplicitMemoryCommandDetector.CommandText("以后使用中文交流", false)
        )).get().extracting(ExplicitMemoryCandidate::content)
                .isEqualTo("用户偏好使用中文交流");
        assertThat(parser.parse(
                new ExplicitMemoryCommandDetector.CommandText("以后回答简短一些", false)
        )).get().extracting(ExplicitMemoryCandidate::content)
                .isEqualTo("用户偏好简洁回答");
        assertThat(parser.parse(
                new ExplicitMemoryCommandDetector.CommandText("我常做Java开发", false)
        )).get().extracting(ExplicitMemoryCandidate::content)
                .isEqualTo("用户常用工作范围是Java开发");
    }

    @Test
    void rejectsUnsupportedAndCrossCategoryPayloads() {
        assertThat(parser.parse(
                new ExplicitMemoryCommandDetector.CommandText("叫我小王", false)
        )).isEmpty();
        assertThat(parser.parse(
                new ExplicitMemoryCommandDetector.CommandText("以后使用中文并简洁回答", false)
        )).isEmpty();
    }
}

