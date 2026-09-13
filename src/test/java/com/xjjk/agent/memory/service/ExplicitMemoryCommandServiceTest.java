package com.xjjk.agent.memory.service;

import com.xjjk.agent.chat.domain.ChatTurnContext;
import com.xjjk.agent.common.api.ApiErrorCode;
import com.xjjk.agent.common.exception.BusinessException;
import com.xjjk.agent.memory.config.UserMemoryProperties;
import com.xjjk.agent.memory.domain.ExplicitMemoryCandidate;
import com.xjjk.agent.memory.domain.ExplicitMemoryCommandResult;
import com.xjjk.agent.memory.domain.ExplicitMemoryResolution;
import com.xjjk.agent.memory.domain.MemoryCategory;
import com.xjjk.agent.memory.domain.MemoryRetentionType;
import com.xjjk.agent.memory.observation.UserMemoryMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ExplicitMemoryCommandServiceTest {

    private final HybridExplicitMemoryResolver resolver = mock(HybridExplicitMemoryResolver.class);
    private final MemorySensitiveContentPolicy sensitivePolicy = mock(MemorySensitiveContentPolicy.class);
    private final ExplicitMemoryCandidateValidator validator = mock(ExplicitMemoryCandidateValidator.class);
    private final ExplicitMemoryWriteService writer = mock(ExplicitMemoryWriteService.class);
    private final UserMemoryPolicyService policy = mock(UserMemoryPolicyService.class);
    private ExplicitMemoryCommandService service;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        when(policy.isMemoryEnabled(1L, 2L)).thenReturn(true);
        when(sensitivePolicy.isAllowed(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        service = service(properties(true));
    }

    @Test
    void noneContinuesNormalChatPath() {
        when(resolver.mightContainExplicitMemory("今天下雨吗")).thenReturn(false);

        assertThat(service.handle(turn(), "今天下雨吗"))
                .isEqualTo(ExplicitMemoryCommandResult.notHandled());
        verify(resolver, never()).resolve(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void semanticNoneContinuesNormalChatPath() {
        String message = "以后订单退款要怎么处理";
        when(resolver.mightContainExplicitMemory(message)).thenReturn(true);
        when(resolver.resolve(message)).thenReturn(ExplicitMemoryResolution.none());

        assertThat(service.handle(turn(), message))
                .isEqualTo(ExplicitMemoryCommandResult.notHandled());
        assertThat(resolutionCount("NONE", "NONE")).isEqualTo(1.0);
    }

    @Test
    void clarificationIsHandledWithoutWriting() {
        String message = "以后这样就行";
        when(resolver.mightContainExplicitMemory(message)).thenReturn(true);
        when(resolver.resolve(message)).thenReturn(ExplicitMemoryResolution.clarify(
                ExplicitMemoryResolution.Path.SEMANTIC_PATH));

        ExplicitMemoryCommandResult result = service.handle(turn(), message);

        assertThat(result.handled()).isTrue();
        assertThat(result.saved()).isFalse();
        assertThat(result.assistantText()).isEqualTo("你希望我记住什么？请把需要长期记住的内容说清楚。");
        verifyNoInteractions(validator, writer);
        assertThat(resolutionCount("SEMANTIC_PATH", "CLARIFY")).isEqualTo(1.0);
    }

    @Test
    void validatesPersistsAndAcknowledgesOnlyAfterWriterSucceeds() {
        String message = "你以后都叫我石海文";
        ExplicitMemoryCandidate candidate = candidate();
        when(resolver.mightContainExplicitMemory(message)).thenReturn(true);
        when(resolver.resolve(message)).thenReturn(ExplicitMemoryResolution.save(candidate,
                ExplicitMemoryResolution.Path.SEMANTIC_PATH, 0.98));
        when(validator.validate(candidate, message, false)).thenReturn(candidate);
        when(writer.save(turn(), candidate)).thenReturn(
                new ExplicitMemoryWriteService.SaveResult("memory-1", "用户希望被称为石海文"));

        assertThat(service.handle(turn(), message)).isEqualTo(new ExplicitMemoryCommandResult(
                true, true, "好的，已记住：用户希望被称为石海文", "memory-1"));
        verify(writer).save(turn(), candidate);
        assertThat(resolutionCount("SEMANTIC_PATH", "SAVED")).isEqualTo(1.0);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "请永久记住我叫小石",
            "请永远记住我叫小石",
            "请始终保存我的回答偏好",
            "请一直保留我喜欢简洁回答的偏好",
            "请长期记住我叫小石",
            "请长期保存我叫小石"
    })
    void recognizesControlledPermanentRetentionCommands(String message) {
        ExplicitMemoryCandidate candidate = prepareResolvedSave(message, true);

        assertThat(service.handle(turn(), message).saved()).isTrue();

        verify(validator).validate(candidate, message, true);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "请记住我长期从事Java开发",
            "我长期从事Java开发，请记住",
            "请记住永久合同内容"
    })
    void doesNotUpgradeRetentionForOrdinaryLongTermFactWording(String message) {
        ExplicitMemoryCandidate candidate = prepareResolvedSave(message, false);

        assertThat(service.handle(turn(), message).saved()).isTrue();

        verify(validator).validate(candidate, message, false);
    }

    @Test
    void rejectsSensitiveCandidateBeforeSemanticModelCall() {
        String message = "请记住我的手机号是13800138000";
        when(resolver.mightContainExplicitMemory(message)).thenReturn(true);
        when(sensitivePolicy.isAllowed(message)).thenReturn(false);

        assertThat(service.handle(turn(), message)).isEqualTo(new ExplicitMemoryCommandResult(
                true, false, "这类内容不适合作为长期记忆保存。", null));
        verify(resolver, never()).resolve(message);
        verifyNoInteractions(validator, writer);
        assertThat(resolutionCount("NONE", "POLICY_REJECTED")).isEqualTo(1.0);
    }

    @Test
    void modelFailureNeverReturnsSuccessWording() {
        String message = "你以后都叫我石海文";
        when(resolver.mightContainExplicitMemory(message)).thenReturn(true);
        when(resolver.resolve(message)).thenThrow(new ExplicitMemoryExtractionException(
                ExplicitMemoryExtractionException.Code.MODEL_TIMEOUT, "timeout"));

        assertThatThrownBy(() -> service.handle(turn(), message))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ApiErrorCode.MEMORY_WRITE_FAILED));
        verifyNoInteractions(validator, writer);
        assertThat(resolutionCount("NONE", "MODEL_FAILURE")).isEqualTo(1.0);
    }

    @Test
    void databaseFailureNeverReturnsSuccessWording() {
        String message = "你以后都叫我石海文";
        ExplicitMemoryCandidate candidate = candidate();
        when(resolver.mightContainExplicitMemory(message)).thenReturn(true);
        when(resolver.resolve(message)).thenReturn(ExplicitMemoryResolution.save(candidate,
                ExplicitMemoryResolution.Path.SEMANTIC_PATH, 0.98));
        when(validator.validate(candidate, message, false)).thenReturn(candidate);
        when(writer.save(turn(), candidate)).thenThrow(
                new DataAccessResourceFailureException("database unavailable"));

        assertThatThrownBy(() -> service.handle(turn(), message))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ApiErrorCode.MEMORY_WRITE_FAILED));
        assertThat(resolutionCount("SEMANTIC_PATH", "PERSISTENCE_FAILURE")).isEqualTo(1.0);
    }

    @Test
    void globalFlagSkipsCandidateGateModelAndWrite() {
        service = service(properties(false));

        assertThat(service.handle(turn(), "你以后都叫我石海文"))
                .isEqualTo(ExplicitMemoryCommandResult.notHandled());
        verifyNoInteractions(resolver, validator, writer);
    }

    @Test
    void userSwitchSkipsModelAndWrite() {
        String message = "你以后都叫我石海文";
        when(resolver.mightContainExplicitMemory(message)).thenReturn(true);
        when(policy.isMemoryEnabled(1L, 2L)).thenReturn(false);

        assertThat(service.handle(turn(), message)).isEqualTo(new ExplicitMemoryCommandResult(
                true, false, "记忆功能已关闭，可在“我的记忆”中开启。", null));
        verify(resolver, never()).resolve(message);
        verifyNoInteractions(validator, writer);
    }

    @Test
    void defersSavedMetricsUntilOuterTransactionCommits() {
        String message = "你以后都叫我石海文";
        ExplicitMemoryCandidate candidate = candidate();
        when(resolver.mightContainExplicitMemory(message)).thenReturn(true);
        when(resolver.resolve(message)).thenReturn(ExplicitMemoryResolution.save(candidate,
                ExplicitMemoryResolution.Path.SEMANTIC_PATH, 0.98));
        when(validator.validate(candidate, message, false)).thenReturn(candidate);
        when(writer.save(turn(), candidate)).thenReturn(
                new ExplicitMemoryWriteService.SaveResult("memory-1", candidate.content()));

        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThat(service.handle(turn(), message).saved()).isTrue();
            assertThat(resolutionCount("SEMANTIC_PATH", "SAVED")).isZero();
            for (TransactionSynchronization synchronization
                    : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
                synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
            }
            assertThat(resolutionCount("SEMANTIC_PATH", "SAVED")).isEqualTo(1.0);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private ExplicitMemoryCommandService service(UserMemoryProperties properties) {
        return new ExplicitMemoryCommandService(properties, resolver, sensitivePolicy,
                validator, writer, policy, new UserMemoryMetrics(meterRegistry));
    }

    private double resolutionCount(String path, String outcome) {
        var counter = meterRegistry.find("agent.user.memory.explicit.resolution")
                .tags("path", path, "outcome", outcome).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private ExplicitMemoryCandidate candidate() {
        return new ExplicitMemoryCandidate(MemoryCategory.PROFILE_PREFERRED_NAME,
                "profile.preferred_name", "用户希望被称为石海文", "你以后都叫我石海文",
                MemoryRetentionType.NORMAL);
    }

    private ExplicitMemoryCandidate prepareResolvedSave(
            String message,
            boolean permanent) {
        ExplicitMemoryCandidate candidate = candidate();
        when(resolver.mightContainExplicitMemory(message)).thenReturn(true);
        when(resolver.resolve(message)).thenReturn(ExplicitMemoryResolution.save(
                candidate, ExplicitMemoryResolution.Path.SEMANTIC_PATH, 0.98));
        when(validator.validate(candidate, message, permanent)).thenReturn(candidate);
        when(writer.save(turn(), candidate)).thenReturn(
                new ExplicitMemoryWriteService.SaveResult("memory-1", candidate.content()));
        return candidate;
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
