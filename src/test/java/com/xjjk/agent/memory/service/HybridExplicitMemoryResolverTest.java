package com.xjjk.agent.memory.service;

import com.xjjk.agent.memory.config.ExplicitMemorySemanticProperties;
import com.xjjk.agent.memory.domain.ExplicitMemoryCandidate;
import com.xjjk.agent.memory.domain.ExplicitMemoryResolution;
import com.xjjk.agent.memory.domain.MemoryCategory;
import com.xjjk.agent.memory.domain.MemoryRetentionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HybridExplicitMemoryResolverTest {

    private final ExplicitMemoryExtractor extractor = mock(ExplicitMemoryExtractor.class);
    private HybridExplicitMemoryResolver resolver;

    @BeforeEach
    void setUp() {
        MemoryCategoryContentPolicy categoryPolicy = new MemoryCategoryContentPolicy();
        resolver = new HybridExplicitMemoryResolver(
                new ExplicitMemoryCommandDetector(512),
                new DeterministicExplicitMemoryCandidateParser(categoryPolicy),
                new ExplicitMemoryCandidateGate(512),
                extractor,
                new ExplicitMemorySemanticProperties(0.85));
    }

    @Test
    void usesDeterministicFastPathWithoutCallingModel() {
        ExplicitMemoryResolution result = resolver.resolve("请永久记住：叫我老师。");

        assertThat(result.action()).isEqualTo(ExplicitMemoryResolution.Action.SAVE);
        assertThat(result.path()).isEqualTo(ExplicitMemoryResolution.Path.FAST_PATH);
        assertThat(result.confidence()).isEqualTo(1.0);
        assertThat(result.candidate().retentionType()).isEqualTo(MemoryRetentionType.PERMANENT);
        verifyNoInteractions(extractor);
    }

    @Test
    void sendsNaturalMemoryInstructionToSemanticModel() {
        String message = "你以后都叫我石海文";
        ExplicitMemoryResolution semantic = ExplicitMemoryResolution.save(candidate(),
                ExplicitMemoryResolution.Path.SEMANTIC_PATH, 0.98);
        when(extractor.resolve(message)).thenReturn(semantic);

        assertThat(resolver.resolve(message)).isEqualTo(semantic);
    }

    @Test
    void ignoresOrdinaryBusinessQuestionWithoutCallingModel() {
        assertThat(resolver.resolve("签收后多久可以退款"))
                .isEqualTo(ExplicitMemoryResolution.none());
        verifyNoInteractions(extractor);
    }

    @Test
    void convertsLowConfidenceSaveToClarification() {
        String message = "你以后都叫我石海文";
        when(extractor.resolve(message)).thenReturn(ExplicitMemoryResolution.save(candidate(),
                ExplicitMemoryResolution.Path.SEMANTIC_PATH, 0.84));

        assertThat(resolver.resolve(message)).isEqualTo(ExplicitMemoryResolution.clarify(
                ExplicitMemoryResolution.Path.SEMANTIC_PATH));
    }

    private ExplicitMemoryCandidate candidate() {
        return new ExplicitMemoryCandidate(MemoryCategory.PROFILE_PREFERRED_NAME,
                "profile.preferred_name", "用户希望被称为石海文", "你以后都叫我石海文",
                MemoryRetentionType.NORMAL);
    }
}
