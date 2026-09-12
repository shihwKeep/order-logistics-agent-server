package com.xjjk.agent.memory.service;

import com.xjjk.agent.memory.config.ExplicitMemorySemanticProperties;
import com.xjjk.agent.memory.domain.ExplicitMemoryResolution;

import java.util.Objects;
import java.util.Optional;

/** Deterministic fast path backed by a bounded semantic fallback. */
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
        return detector.detect(message).isPresent() || gate.mightContainExplicitMemory(message);
    }

    public ExplicitMemoryResolution resolve(String message) {
        Optional<ExplicitMemoryCommandDetector.CommandText> command = detector.detect(message);
        if (command.isPresent()) {
            var fastCandidate = parser.parse(command.get());
            if (fastCandidate.isPresent()) {
                return ExplicitMemoryResolution.save(
                        fastCandidate.get(), ExplicitMemoryResolution.Path.FAST_PATH, 1.0);
            }
        }
        if (!gate.mightContainExplicitMemory(message)) {
            return ExplicitMemoryResolution.none();
        }
        ExplicitMemoryResolution semantic = extractor.resolve(message);
        if (semantic.action() == ExplicitMemoryResolution.Action.SAVE
                && semantic.confidence() < properties.confidenceThreshold()) {
            return ExplicitMemoryResolution.clarify(
                    ExplicitMemoryResolution.Path.SEMANTIC_PATH);
        }
        return semantic;
    }
}
