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
        // 第一步：使用保守规则识别“询问本人已保存事实”的问题；未唯一命中不接管本轮聊天。
        Optional<DirectMemoryQuestionType> classified = classifier.classify(query);
        if (classified.isEmpty()) {
            return DeterministicUserMemoryAnswerResult.notHandled();
        }
        DirectMemoryQuestionType type = classified.get();
        // 第二步：根据问题类型限定 predicate、旧类别和时间范围，直接从 MySQL 事实源召回，
        // 不依赖 ES/Milvus 的语义候选，也不让大模型猜测用户属性。
        UserMemoryRecallResult recalled = recallService.recallByPredicates(
                identity, type.predicateNames(), type.memoryCategory(),
                type.temporalScope());
        if (recalled.status() == UserMemoryRecallStatus.NOT_INITIALIZED) {
            // 用户尚未初始化记忆时回落普通 Agent，保持未启用记忆用户的原有体验。
            return fallThrough(requestId, type);
        }
        if (recalled.status() == UserMemoryRecallStatus.DISABLED) {
            // 用户主动关闭记忆后不输出历史事实，也不把“关闭”当作系统故障。
            return fallThrough(requestId, type);
        }
        if (recalled.status() != UserMemoryRecallStatus.AVAILABLE) {
            // 数据库终审等基础能力异常时返回明确不可用提示，禁止回落模型后编造答案。
            return handled(requestId, type,
                    DeterministicUserMemoryAnswerResult.Outcome.UNAVAILABLE,
                    UNAVAILABLE);
        }

        // 第三步：候选已经过归属、代次、状态、有效期和抑制终审；渲染器仍需确认
        // 结构化值能够安全转换为该问题类型的固定回答。
        for (RecalledMemory memory : recalled.memories()) {
            Optional<String> rendered = renderer.render(type, memory);
            if (rendered.isPresent()) {
                return handled(requestId, type,
                        DeterministicUserMemoryAnswerResult.Outcome.ANSWERED,
                        rendered.get());
            }
        }
        // 没有找到可渲染的权威事实时不声称“不知道用户”，而是释放给后续普通问答链路。
        return fallThrough(requestId, type);
    }

    private DeterministicUserMemoryAnswerResult handled(
            String requestId,
            DirectMemoryQuestionType type,
            DeterministicUserMemoryAnswerResult.Outcome outcome,
            String text) {
        // handled 结果会让 ChatTurnRunner 直接输出固定正文并结束本轮意图路由。
        metrics.directAnswer(type.name(), outcome.name());
        log.info("user_memory_direct_answer requestId={} questionType={} outcome={}",
                requestId, type.name(), outcome.name());
        return new DeterministicUserMemoryAnswerResult(outcome, text);
    }

    private DeterministicUserMemoryAnswerResult fallThrough(
            String requestId,
            DirectMemoryQuestionType type) {
        // FALLTHROUGH 只记录分类命中但未直答，不代表本轮请求失败。
        metrics.directAnswer(type.name(), "FALLTHROUGH");
        log.info("user_memory_direct_answer requestId={} questionType={} outcome=FALLTHROUGH",
                requestId, type.name());
        return DeterministicUserMemoryAnswerResult.notHandled();
    }
}
