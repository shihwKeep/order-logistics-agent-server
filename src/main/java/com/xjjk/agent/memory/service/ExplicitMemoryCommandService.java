package com.xjjk.agent.memory.service;

import com.xjjk.agent.chat.domain.ChatTurnContext;
import com.xjjk.agent.common.api.ApiErrorCode;
import com.xjjk.agent.common.exception.BusinessException;
import com.xjjk.agent.memory.config.UserMemoryProperties;
import com.xjjk.agent.memory.domain.ExplicitMemoryCandidate;
import com.xjjk.agent.memory.domain.ExplicitMemoryCommandResult;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;

@Service
public class ExplicitMemoryCommandService {

    private static final String REJECTED_TEXT = "这类内容不适合作为长期记忆保存。";

    private final UserMemoryProperties properties;
    private final ExplicitMemoryCommandDetector detector;
    private final MemorySensitiveContentPolicy sensitivePolicy;
    private final ExplicitMemoryExtractor extractor;
    private final ExplicitMemoryCandidateValidator validator;
    private final ExplicitMemoryWriteService writer;

    public ExplicitMemoryCommandService(
            UserMemoryProperties properties,
            ExplicitMemoryCommandDetector detector,
            MemorySensitiveContentPolicy sensitivePolicy,
            ExplicitMemoryExtractor extractor,
            ExplicitMemoryCandidateValidator validator,
            ExplicitMemoryWriteService writer
    ) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.detector = Objects.requireNonNull(detector, "detector");
        this.sensitivePolicy = Objects.requireNonNull(sensitivePolicy, "sensitivePolicy");
        this.extractor = Objects.requireNonNull(extractor, "extractor");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.writer = Objects.requireNonNull(writer, "writer");
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
            return rejected();
        }
        try {
            ExplicitMemoryCandidate extracted = extractor.extract(detected.get(), userMessage);
            ExplicitMemoryCandidate candidate = validator.validate(
                    extracted, userMessage, detected.get().permanent());
            ExplicitMemoryWriteService.SaveResult saved = writer.save(turn, candidate);
            return new ExplicitMemoryCommandResult(
                    true, true, "好的，已记住：" + saved.content(), saved.memoryId());
        } catch (IllegalArgumentException rejected) {
            return rejected();
        } catch (ExplicitMemoryExtractionException unavailable) {
            throw new BusinessException(ApiErrorCode.MEMORY_WRITE_FAILED);
        }
    }

    private static ExplicitMemoryCommandResult rejected() {
        return new ExplicitMemoryCommandResult(true, false, REJECTED_TEXT, null);
    }
}
