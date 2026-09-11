package com.xjjk.agent.memory.service;

import com.xjjk.agent.chat.domain.ChatTurnContext;
import com.xjjk.agent.common.api.ApiErrorCode;
import com.xjjk.agent.common.exception.BusinessException;
import com.xjjk.agent.memory.config.UserMemoryProperties;
import com.xjjk.agent.memory.domain.ExplicitMemoryCandidate;
import com.xjjk.agent.memory.domain.ExplicitMemoryCommandResult;
import com.xjjk.agent.memory.observation.UserMemoryMetrics;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;
import java.util.Optional;

@Service
public class ExplicitMemoryCommandService {

    private static final String REJECTED_TEXT = "这类内容不适合作为长期记忆保存。";

    private final UserMemoryProperties properties;
    private final ExplicitMemoryCommandDetector detector;
    private final MemorySensitiveContentPolicy sensitivePolicy;
    private final DeterministicExplicitMemoryCandidateParser parser;
    private final ExplicitMemoryCandidateValidator validator;
    private final ExplicitMemoryWriteService writer;
    private final UserMemoryMetrics metrics;

    public ExplicitMemoryCommandService(
            UserMemoryProperties properties,
            ExplicitMemoryCommandDetector detector,
            MemorySensitiveContentPolicy sensitivePolicy,
            DeterministicExplicitMemoryCandidateParser parser,
            ExplicitMemoryCandidateValidator validator,
            ExplicitMemoryWriteService writer,
            UserMemoryMetrics metrics
    ) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.detector = Objects.requireNonNull(detector, "detector");
        this.sensitivePolicy = Objects.requireNonNull(sensitivePolicy, "sensitivePolicy");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    public ExplicitMemoryCommandResult handle(ChatTurnContext turn, String userMessage) {
        if (!properties.enabled()) {
            return ExplicitMemoryCommandResult.notHandled();
        }
        Optional<ExplicitMemoryCommandDetector.CommandText> detected = detector.detect(userMessage);
        if (detected.isEmpty()) {
            return ExplicitMemoryCommandResult.notHandled();
        }
        if (!sensitivePolicy.isAllowed(userMessage)) {
            metrics.rejected("explicit_save", ApiErrorCode.MEMORY_CONTENT_REJECTED);
            return rejected();
        }
        try {
            ExplicitMemoryCandidate extracted = parser.parse(detected.get())
                    .orElseThrow(() -> new IllegalArgumentException("MEMORY_CONTENT_REJECTED"));
            ExplicitMemoryCandidate candidate = validator.validate(
                    extracted, userMessage, detected.get().permanent());
            ExplicitMemoryWriteService.SaveResult saved = writer.save(turn, candidate);
            recordSuccessAfterCommit();
            return new ExplicitMemoryCommandResult(
                    true, true, "好的，已记住：" + saved.content(), saved.memoryId());
        } catch (IllegalArgumentException rejected) {
            metrics.rejected("explicit_save", ApiErrorCode.MEMORY_CONTENT_REJECTED);
            return rejected();
        } catch (ExplicitMemoryExtractionException unavailable) {
            metrics.failure("explicit_save", ApiErrorCode.MEMORY_WRITE_FAILED);
            throw new BusinessException(ApiErrorCode.MEMORY_WRITE_FAILED);
        } catch (BusinessException failure) {
            metrics.failure("explicit_save", failure.errorCode());
            throw failure;
        } catch (DataAccessException | TransactionException failure) {
            metrics.failure("explicit_save", ApiErrorCode.MEMORY_WRITE_FAILED);
            throw new BusinessException(ApiErrorCode.MEMORY_WRITE_FAILED);
        } catch (RuntimeException failure) {
            metrics.failure("explicit_save", ApiErrorCode.INTERNAL_SERVER_ERROR);
            throw failure;
        }
    }

    private static ExplicitMemoryCommandResult rejected() {
        return new ExplicitMemoryCommandResult(true, false, REJECTED_TEXT, null);
    }

    private void recordSuccessAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            metrics.success("explicit_save", 1);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            private boolean committed;

            @Override
            public void afterCommit() {
                committed = true;
                metrics.success("explicit_save", 1);
            }

            @Override
            public void afterCompletion(int status) {
                if (!committed && status != STATUS_COMMITTED) {
                    metrics.failure("explicit_save", ApiErrorCode.MEMORY_WRITE_FAILED);
                }
            }
        });
    }
}
