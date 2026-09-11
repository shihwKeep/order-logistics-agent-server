package com.xjjk.agent.memory.service;

import com.xjjk.agent.chat.domain.ChatTurnContext;
import com.xjjk.agent.memory.config.UserMemoryProperties;
import com.xjjk.agent.memory.domain.ExplicitMemoryCandidate;
import com.xjjk.agent.memory.domain.ExplicitMemoryCommandResult;
import com.xjjk.agent.memory.domain.MemoryCategory;
import com.xjjk.agent.memory.domain.MemoryRetentionType;
import com.xjjk.agent.memory.observation.UserMemoryMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExplicitMemoryCommandServiceTest {

    private final ExplicitMemoryExtractor extractor = mock(ExplicitMemoryExtractor.class);
    private final ExplicitMemoryCandidateValidator validator = mock(ExplicitMemoryCandidateValidator.class);
    private final ExplicitMemoryWriteService writer = mock(ExplicitMemoryWriteService.class);
    private ExplicitMemoryCommandService service;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new ExplicitMemoryCommandService(
                properties(true), new ExplicitMemoryCommandDetector(512),
                new MemorySensitiveContentPolicy(new com.xjjk.agent.chat.service.summary.SensitiveContentSanitizer()),
                extractor, validator, writer, new UserMemoryMetrics(meterRegistry));
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
        assertThat(operationCount("explicit_save", "success", "NONE")).isEqualTo(1.0);
    }

    @Test
    void savesSupportedPermanentPreferredNameWithoutDependingOnModelWording() {
        String message = "请永久记住：叫我老师。";
        ExplicitMemoryCandidateValidator strictValidator = new ExplicitMemoryCandidateValidator(
                new MemorySensitiveContentPolicy(
                        new com.xjjk.agent.chat.service.summary.SensitiveContentSanitizer()),
                512,
                512
        );
        ExplicitMemoryCommandService strictService = new ExplicitMemoryCommandService(
                properties(true), new ExplicitMemoryCommandDetector(512),
                new MemorySensitiveContentPolicy(
                        new com.xjjk.agent.chat.service.summary.SensitiveContentSanitizer()),
                extractor, strictValidator, writer, new UserMemoryMetrics(meterRegistry));
        when(extractor.extract(
                new ExplicitMemoryCommandDetector.CommandText("叫我老师", true),
                message
        )).thenReturn(new ExplicitMemoryCandidate(
                MemoryCategory.PROFILE_PREFERRED_NAME,
                "profile.preferred_name",
                "称呼用户为老师",
                message,
                MemoryRetentionType.PERMANENT
        ));
        when(writer.save(eq(turn()), any())).thenReturn(
                new ExplicitMemoryWriteService.SaveResult("memory-1", "用户希望被称为老师")
        );

        ExplicitMemoryCommandResult result = strictService.handle(turn(), message);

        assertThat(result.handled()).isTrue();
        assertThat(result.saved()).isTrue();
        assertThat(result.assistantText()).isEqualTo("好的，已记住：用户希望被称为老师");
    }

    @Test
    void sensitiveExplicitCommandIsHandledButNotSaved() {
        String message = "请记住我的手机号是13800138000";
        assertThat(service.handle(turn(), message))
                .isEqualTo(new ExplicitMemoryCommandResult(
                        true, false, "这类内容不适合作为长期记忆保存。", null));
        assertThat(operationCount("explicit_save", "rejected", "MEMORY_CONTENT_REJECTED")).isEqualTo(1.0);
    }

    @Test
    void featureFlagDisablesCommandHandling() {
        service = new ExplicitMemoryCommandService(
                properties(false), new ExplicitMemoryCommandDetector(512),
                new MemorySensitiveContentPolicy(new com.xjjk.agent.chat.service.summary.SensitiveContentSanitizer()),
                extractor, validator, writer, new UserMemoryMetrics(meterRegistry));
        assertThat(service.handle(turn(), "请记住以后回答简短一些"))
                .isEqualTo(ExplicitMemoryCommandResult.notHandled());
    }

    @Test
    void recordsUnexpectedPersistenceRuntimeFailureWithoutContentTags() {
        String message = "请记住以后回答简短一些";
        var command = new ExplicitMemoryCommandDetector.CommandText("以后回答简短一些", false);
        ExplicitMemoryCandidate candidate = candidate();
        when(extractor.extract(command, message)).thenReturn(candidate);
        when(validator.validate(candidate, message, false)).thenReturn(candidate);
        when(writer.save(turn(), candidate)).thenThrow(new IllegalStateException("database failure"));

        assertThatThrownBy(() -> service.handle(turn(), message))
                .isInstanceOf(IllegalStateException.class);
        assertThat(operationCount("explicit_save", "failure", "INTERNAL_SERVER_ERROR"))
                .isEqualTo(1.0);
    }

    @Test
    void mapsDatabaseFailureToStableMemoryWriteFailure() {
        String message = "请记住以后回答简短一些";
        var command = new ExplicitMemoryCommandDetector.CommandText("以后回答简短一些", false);
        ExplicitMemoryCandidate candidate = candidate();
        when(extractor.extract(command, message)).thenReturn(candidate);
        when(validator.validate(candidate, message, false)).thenReturn(candidate);
        when(writer.save(turn(), candidate))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));

        assertThatThrownBy(() -> service.handle(turn(), message))
                .isInstanceOfSatisfying(com.xjjk.agent.common.exception.BusinessException.class,
                        error -> assertThat(error.errorCode())
                                .isEqualTo(com.xjjk.agent.common.api.ApiErrorCode.MEMORY_WRITE_FAILED));
        assertThat(operationCount("explicit_save", "failure", "MEMORY_WRITE_FAILED"))
                .isEqualTo(1.0);
    }

    @Test
    void defersSuccessMetricUntilOuterTransactionCommitsAndRecordsRollback() {
        String message = "请记住以后回答简短一些";
        var command = new ExplicitMemoryCommandDetector.CommandText("以后回答简短一些", false);
        ExplicitMemoryCandidate candidate = candidate();
        when(extractor.extract(command, message)).thenReturn(candidate);
        when(validator.validate(candidate, message, false)).thenReturn(candidate);
        when(writer.save(turn(), candidate))
                .thenReturn(new ExplicitMemoryWriteService.SaveResult("memory-1", "用户偏好简洁回答"));

        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThat(service.handle(turn(), message).saved()).isTrue();
            assertThat(operationCount("explicit_save", "success", "NONE")).isZero();

            for (TransactionSynchronization synchronization
                    : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            }

            assertThat(operationCount("explicit_save", "success", "NONE")).isZero();
            assertThat(operationCount("explicit_save", "failure", "MEMORY_WRITE_FAILED"))
                    .isEqualTo(1.0);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private double operationCount(String operation, String outcome, String code) {
        var counter = meterRegistry.find("agent.user.memory.operation")
                .tags("operation", operation, "outcome", outcome, "code", code)
                .counter();
        return counter == null ? 0.0 : counter.count();
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
