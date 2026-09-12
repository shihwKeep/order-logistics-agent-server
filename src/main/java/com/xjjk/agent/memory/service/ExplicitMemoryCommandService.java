package com.xjjk.agent.memory.service;

import com.xjjk.agent.chat.domain.ChatTurnContext;
import com.xjjk.agent.common.api.ApiErrorCode;
import com.xjjk.agent.common.exception.BusinessException;
import com.xjjk.agent.memory.config.UserMemoryProperties;
import com.xjjk.agent.memory.domain.ExplicitMemoryCandidate;
import com.xjjk.agent.memory.domain.ExplicitMemoryCommandResult;
import com.xjjk.agent.memory.domain.ExplicitMemoryResolution;
import com.xjjk.agent.memory.observation.UserMemoryMetrics;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;

@Service
public class ExplicitMemoryCommandService {

    private static final String REJECTED_TEXT = "这类内容不适合作为长期记忆保存。";
    private static final String DISABLED_TEXT = "记忆功能已关闭，可在“我的记忆”中开启。";
    private static final String CLARIFY_TEXT = "你希望我记住什么？请把需要长期记住的内容说清楚。";

    private final UserMemoryProperties properties;
    private final HybridExplicitMemoryResolver resolver;
    private final MemorySensitiveContentPolicy sensitivePolicy;
    private final ExplicitMemoryCandidateValidator validator;
    private final ExplicitMemoryWriteService writer;
    private final UserMemoryPolicyService policy;
    private final UserMemoryMetrics metrics;

    public ExplicitMemoryCommandService(
            UserMemoryProperties properties,
            HybridExplicitMemoryResolver resolver,
            MemorySensitiveContentPolicy sensitivePolicy,
            ExplicitMemoryCandidateValidator validator,
            ExplicitMemoryWriteService writer,
            UserMemoryPolicyService policy,
            UserMemoryMetrics metrics
    ) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.sensitivePolicy = Objects.requireNonNull(sensitivePolicy, "sensitivePolicy");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    public ExplicitMemoryCommandResult handle(ChatTurnContext turn, String userMessage) {
        if (!properties.enabled()) {
            return ExplicitMemoryCommandResult.notHandled();
        }
        if (!resolver.mightContainExplicitMemory(userMessage)) {
            return ExplicitMemoryCommandResult.notHandled();
        }
        ExplicitMemoryResolution.Path resolutionPath = ExplicitMemoryResolution.Path.NONE;
        boolean persistenceStarted = false;
        try {
            if (!policy.isMemoryEnabled(turn.tenantId(), turn.userId())) {
                return disabled();
            }
            if (!sensitivePolicy.isAllowed(userMessage)) {
                metrics.rejected("explicit_save", ApiErrorCode.MEMORY_CONTENT_REJECTED);
                metrics.explicitResolution("NONE", "POLICY_REJECTED");
                return rejected();
            }
            ExplicitMemoryResolution resolution = resolver.resolve(userMessage);
            resolutionPath = resolution.path();
            if (resolution.action() == ExplicitMemoryResolution.Action.NONE) {
                metrics.explicitResolution("NONE", "NONE");
                return ExplicitMemoryCommandResult.notHandled();
            }
            if (resolution.action() == ExplicitMemoryResolution.Action.CLARIFY) {
                metrics.explicitResolution(resolutionPath.name(), "CLARIFY");
                return new ExplicitMemoryCommandResult(true, false, CLARIFY_TEXT, null);
            }
            ExplicitMemoryCandidate extracted = resolution.candidate();
            ExplicitMemoryCandidate candidate = validator.validate(
                    extracted, userMessage, requestsPermanentRetention(userMessage));
            persistenceStarted = true;
            ExplicitMemoryWriteService.SaveResult saved = writer.save(turn, candidate);
            recordSuccessAfterCommit(resolutionPath);
            return new ExplicitMemoryCommandResult(
                    true, true, "好的，已记住：" + saved.content(), saved.memoryId());
        } catch (IllegalArgumentException rejected) {
            metrics.rejected("explicit_save", ApiErrorCode.MEMORY_CONTENT_REJECTED);
            metrics.explicitResolution(resolutionPath.name(), "POLICY_REJECTED");
            return rejected();
        } catch (ExplicitMemoryExtractionException unavailable) {
            metrics.failure("explicit_save", ApiErrorCode.MEMORY_WRITE_FAILED);
            metrics.explicitResolution("NONE", "MODEL_FAILURE");
            throw new BusinessException(ApiErrorCode.MEMORY_WRITE_FAILED);
        } catch (UserMemoryDisabledException disabled) {
            return disabled();
        } catch (BusinessException failure) {
            metrics.failure("explicit_save", failure.errorCode());
            if (persistenceStarted) {
                metrics.explicitResolution(resolutionPath.name(), "PERSISTENCE_FAILURE");
            }
            throw failure;
        } catch (DataAccessException | TransactionException failure) {
            metrics.failure("explicit_save", ApiErrorCode.MEMORY_WRITE_FAILED);
            metrics.explicitResolution(resolutionPath.name(), "PERSISTENCE_FAILURE");
            throw new BusinessException(ApiErrorCode.MEMORY_WRITE_FAILED);
        } catch (RuntimeException failure) {
            metrics.failure("explicit_save", ApiErrorCode.INTERNAL_SERVER_ERROR);
            if (persistenceStarted) {
                metrics.explicitResolution(resolutionPath.name(), "PERSISTENCE_FAILURE");
            }
            throw failure;
        }
    }

    private static ExplicitMemoryCommandResult rejected() {
        return new ExplicitMemoryCommandResult(true, false, REJECTED_TEXT, null);
    }

    private ExplicitMemoryCommandResult disabled() {
        metrics.rejected("explicit_save", ApiErrorCode.MEMORY_DISABLED);
        metrics.explicitResolution("NONE", "DISABLED");
        return new ExplicitMemoryCommandResult(true, false, DISABLED_TEXT, null);
    }

    private void recordSuccessAfterCommit(ExplicitMemoryResolution.Path path) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            metrics.success("explicit_save", 1);
            metrics.explicitResolution(path.name(), "SAVED");
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            private boolean committed;

            @Override
            public void afterCommit() {
                committed = true;
                metrics.success("explicit_save", 1);
                metrics.explicitResolution(path.name(), "SAVED");
            }

            @Override
            public void afterCompletion(int status) {
                if (!committed && status != STATUS_COMMITTED) {
                    metrics.failure("explicit_save", ApiErrorCode.MEMORY_WRITE_FAILED);
                }
            }
        });
    }

    private static boolean requestsPermanentRetention(String message) {
        return message != null && message.contains("永久");
    }
}
