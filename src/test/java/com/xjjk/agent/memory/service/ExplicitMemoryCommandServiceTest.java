package com.xjjk.agent.memory.service;

import com.xjjk.agent.chat.domain.ChatTurnContext;
import com.xjjk.agent.memory.config.UserMemoryProperties;
import com.xjjk.agent.memory.domain.ExplicitMemoryCandidate;
import com.xjjk.agent.memory.domain.ExplicitMemoryCommandResult;
import com.xjjk.agent.memory.domain.MemoryCategory;
import com.xjjk.agent.memory.domain.MemoryRetentionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExplicitMemoryCommandServiceTest {

    private final ExplicitMemoryExtractor extractor = mock(ExplicitMemoryExtractor.class);
    private final ExplicitMemoryCandidateValidator validator = mock(ExplicitMemoryCandidateValidator.class);
    private final ExplicitMemoryWriteService writer = mock(ExplicitMemoryWriteService.class);
    private ExplicitMemoryCommandService service;

    @BeforeEach
    void setUp() {
        service = new ExplicitMemoryCommandService(
                properties(true), new ExplicitMemoryCommandDetector(512),
                new MemorySensitiveContentPolicy(new com.xjjk.agent.chat.service.summary.SensitiveContentSanitizer()),
                extractor, validator, writer);
    }

    @Test
    void ignoresNormalConversation() {
        assertThat(service.handle(turn(), "今天下雨吗"))
                .isEqualTo(ExplicitMemoryCommandResult.notHandled());
    }

    @Test
    void extractsValidatesPersistsAndAcknowledgesPureCommand() {
        String message = "请记住以后回答简短一些";
        var command = new ExplicitMemoryCommandDetector.CommandText("以后回答简短一些", false);
        ExplicitMemoryCandidate candidate = candidate();
        when(extractor.extract(command, message)).thenReturn(candidate);
        when(validator.validate(candidate, message, false)).thenReturn(candidate);
        when(writer.save(turn(), candidate))
                .thenReturn(new ExplicitMemoryWriteService.SaveResult("memory-1", "用户偏好简洁回答"));

        assertThat(service.handle(turn(), message))
                .isEqualTo(new ExplicitMemoryCommandResult(
                        true, true, "好的，已记住：用户偏好简洁回答", "memory-1"));
    }

    @Test
    void sensitiveExplicitCommandIsHandledButNotSaved() {
        String message = "请记住我的手机号是13800138000";
        assertThat(service.handle(turn(), message))
                .isEqualTo(new ExplicitMemoryCommandResult(
                        true, false, "这类内容不适合作为长期记忆保存。", null));
    }

    @Test
    void featureFlagDisablesCommandHandling() {
        service = new ExplicitMemoryCommandService(
                properties(false), new ExplicitMemoryCommandDetector(512),
                new MemorySensitiveContentPolicy(new com.xjjk.agent.chat.service.summary.SensitiveContentSanitizer()),
                extractor, validator, writer);
        assertThat(service.handle(turn(), "请记住以后回答简短一些"))
                .isEqualTo(ExplicitMemoryCommandResult.notHandled());
    }

    private ExplicitMemoryCandidate candidate() {
        return new ExplicitMemoryCandidate(MemoryCategory.PREFERENCE_ANSWER_STYLE,
                "preference.answer_style", "用户偏好简洁回答", "以后回答简短一些",
                MemoryRetentionType.NORMAL);
    }

    private ChatTurnContext turn() {
        return new ChatTurnContext(1L, 2L, "conversation", "request",
                "user-message", "assistant-message", "prompt-v1");
    }

    private UserMemoryProperties properties(boolean enabled) {
        return new UserMemoryProperties(enabled, true, 256, 512, 512, 50, 365,
                "memory-test-v1", "qwen-plus", 0.1, Duration.ofSeconds(1), 1, 10);
    }
}
