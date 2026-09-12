package com.xjjk.agent.memory.answer;

import com.xjjk.agent.identity.domain.AgentIdentity;
import com.xjjk.agent.memory.observation.UserMemoryMetrics;
import com.xjjk.agent.memory.recall.RecalledMemory;
import com.xjjk.agent.memory.recall.UserMemoryRecallResult;
import com.xjjk.agent.memory.recall.UserMemoryRecallService;
import com.xjjk.agent.memory.recall.UserMemoryRecallStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/** 对严格分类的本人记忆问题生成不经过模型的确定性回答。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeterministicUserMemoryAnswerService {
    private static final String UNAVAILABLE = "记忆服务暂时不可用，请稍后重试。";

    private final DirectMemoryQuestionClassifier classifier;
    private final UserMemoryRecallService recallService;
    private final DeterministicMemoryAnswerRenderer renderer;
    private final UserMemoryMetrics metrics;

    public DeterministicUserMemoryAnswerResult answer(
            AgentIdentity identity,
            String query,
            String requestId) {
        Optional<DirectMemoryQuestionType> classified = classifier.classify(query);
        if (classified.isEmpty()) {
            return DeterministicUserMemoryAnswerResult.notHandled();
        }
        DirectMemoryQuestionType type = classified.get();
        UserMemoryRecallResult recalled = recallService.recallByCategory(
                identity, type.memoryCategory());
        if (recalled.status() == UserMemoryRecallStatus.NOT_INITIALIZED) {
            return fallThrough(requestId, type);
        }
        if (recalled.status() == UserMemoryRecallStatus.DISABLED) {
            return fallThrough(requestId, type);
        }
        if (recalled.status() != UserMemoryRecallStatus.AVAILABLE) {
            return handled(requestId, type,
                    DeterministicUserMemoryAnswerResult.Outcome.UNAVAILABLE,
                    UNAVAILABLE);
        }

        for (RecalledMemory memory : recalled.memories()) {
            Optional<String> rendered = renderer.render(type, memory);
            if (rendered.isPresent()) {
                return handled(requestId, type,
                        DeterministicUserMemoryAnswerResult.Outcome.ANSWERED,
                        rendered.get());
            }
        }
        return fallThrough(requestId, type);
    }

    private DeterministicUserMemoryAnswerResult handled(
            String requestId,
            DirectMemoryQuestionType type,
            DeterministicUserMemoryAnswerResult.Outcome outcome,
            String text) {
        metrics.directAnswer(type.name(), outcome.name());
        log.info("user_memory_direct_answer requestId={} questionType={} outcome={}",
                requestId, type.name(), outcome.name());
        return new DeterministicUserMemoryAnswerResult(outcome, text);
    }

    private DeterministicUserMemoryAnswerResult fallThrough(
            String requestId,
            DirectMemoryQuestionType type) {
        metrics.directAnswer(type.name(), "FALLTHROUGH");
        log.info("user_memory_direct_answer requestId={} questionType={} outcome=FALLTHROUGH",
                requestId, type.name());
        return DeterministicUserMemoryAnswerResult.notHandled();
    }
}
