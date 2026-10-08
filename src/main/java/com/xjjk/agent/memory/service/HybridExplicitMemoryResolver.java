package com.xjjk.agent.memory.service;

import com.xjjk.agent.memory.config.ExplicitMemorySemanticProperties;
import com.xjjk.agent.memory.domain.ExplicitMemoryResolution;

import java.util.Objects;
import java.util.Optional;

/**
 * 显式记忆的混合解析器：确定性快速路径优先，受限语义模型负责兜底。
 *
 * <p>快速路径提供低延迟和可解释性；语义路径扩展自然语言泛化能力。无论哪条路径命中，
 * 后续仍要经过统一候选校验，解析结果本身不能直接授权写入。</p>
 */
public class HybridExplicitMemoryResolver {

    private final ExplicitMemoryCommandDetector detector;
    private final DeterministicExplicitMemoryCandidateParser parser;
    private final ExplicitMemoryCandidateGate gate;
    private final ExplicitMemoryExtractor extractor;
    private final ExplicitMemorySemanticProperties properties;

    public HybridExplicitMemoryResolver(
            ExplicitMemoryCommandDetector detector,
            DeterministicExplicitMemoryCandidateParser parser,
            ExplicitMemoryCandidateGate gate,
            ExplicitMemoryExtractor extractor,
            ExplicitMemorySemanticProperties properties
    ) {
        this.detector = Objects.requireNonNull(detector, "detector");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.extractor = Objects.requireNonNull(extractor, "extractor");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    public boolean mightContainExplicitMemory(String message) {
        // 明确命令前缀与宽松语义门控任一命中即可进入解析；这里只控制成本，不决定保存。
        return detector.detect(message).isPresent() || gate.mightContainExplicitMemory(message);
    }

    public ExplicitMemoryResolution resolve(String message) {
        // 第一步：优先解析“请记住/永久记住”等封闭命令，避免简单意图也调用模型。
        Optional<ExplicitMemoryCommandDetector.CommandText> command = detector.detect(message);
        if (command.isPresent()) {
            // 第二步：只有正文唯一落入一个受控记忆类别时才接受快速候选；
            // 多类别、未知表达或无法规范化的内容自动进入语义兜底。
            var fastCandidate = parser.parse(command.get());
            if (fastCandidate.isPresent()) {
                return ExplicitMemoryResolution.save(
                        fastCandidate.get(), ExplicitMemoryResolution.Path.FAST_PATH, 1.0);
            }
        }
        // 第三步：连宽松门控都未命中时明确返回 NONE，防止所有聊天都调用记忆模型。
        if (!gate.mightContainExplicitMemory(message)) {
            return ExplicitMemoryResolution.none();
        }
        // 第四步：语义模型只负责分类和抽取，最终 canonicalKey/content 仍由服务端生成。
        ExplicitMemoryResolution semantic = extractor.resolve(message);
        if (semantic.action() == ExplicitMemoryResolution.Action.SAVE
                && semantic.confidence() < properties.confidenceThreshold()) {
            // 模型认为应保存但置信度不足时要求用户澄清，不能静默保存不确定事实。
            return ExplicitMemoryResolution.clarify(
                    ExplicitMemoryResolution.Path.SEMANTIC_PATH);
        }
        return semantic;
    }
}
