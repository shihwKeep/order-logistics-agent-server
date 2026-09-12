package com.xjjk.agent.memory.domain;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 将语义决策、模型候选数与最终可信事实绑定，供事务提交生成安全结果码。 */
public record ImplicitMemoryExtractionBatch(
        int modelCandidateCount,
        List<ValidatedMemoryFact> acceptedCandidates,
        MemoryExtractionResultCode emptyResultCode
) {

    private static final Set<MemoryExtractionResultCode> REJECTIONS = Set.of(
            MemoryExtractionResultCode.ALL_REJECTED,
            MemoryExtractionResultCode.REJECTED_SCHEMA,
            MemoryExtractionResultCode.REJECTED_EVIDENCE,
            MemoryExtractionResultCode.REJECTED_SENSITIVE,
            MemoryExtractionResultCode.REJECTED_STABILITY,
            MemoryExtractionResultCode.REJECTED_CONFIDENCE,
            MemoryExtractionResultCode.REJECTED_UNSUPPORTED,
            MemoryExtractionResultCode.REJECTED_CONTRADICTED,
            MemoryExtractionResultCode.REJECTED_UNCERTAIN);

    public ImplicitMemoryExtractionBatch {
        Objects.requireNonNull(acceptedCandidates, "acceptedCandidates");
        acceptedCandidates = List.copyOf(acceptedCandidates);
        if (modelCandidateCount < 0 || modelCandidateCount < acceptedCandidates.size()) {
            throw new IllegalArgumentException("accepted candidates exceed model candidates");
        }
        if (acceptedCandidates.isEmpty()) {
            requireValidEmptyResult(modelCandidateCount, emptyResultCode);
        } else if (emptyResultCode != null) {
            throw new IllegalArgumentException("non-empty batch cannot have empty result code");
        }
    }

    public static ImplicitMemoryExtractionBatch observed(
            int modelCandidateCount,
            List<ValidatedMemoryFact> acceptedCandidates) {
        Objects.requireNonNull(acceptedCandidates, "acceptedCandidates");
        List<ValidatedMemoryFact> copy = List.copyOf(acceptedCandidates);
        if (modelCandidateCount < copy.size()) {
            throw new IllegalArgumentException("accepted candidates exceed model candidates");
        }
        MemoryExtractionResultCode emptyCode = copy.isEmpty()
                ? (modelCandidateCount == 0
                    ? MemoryExtractionResultCode.MODEL_EMPTY
                    : MemoryExtractionResultCode.ALL_REJECTED)
                : null;
        return new ImplicitMemoryExtractionBatch(modelCandidateCount, copy, emptyCode);
    }

    public static ImplicitMemoryExtractionBatch decision(MemoryDecision decision) {
        Objects.requireNonNull(decision, "decision");
        return switch (decision) {
            case IGNORE -> empty(MemoryExtractionResultCode.IGNORE);
            case SESSION_ONLY -> empty(MemoryExtractionResultCode.SESSION_ONLY);
            case LONG_TERM -> throw new IllegalArgumentException(
                    "LONG_TERM decision requires observed candidates");
        };
    }

    public static ImplicitMemoryExtractionBatch rejected(
            int modelCandidateCount,
            MemoryExtractionResultCode rejection) {
        if (!REJECTIONS.contains(rejection)) {
            throw new IllegalArgumentException("result code is not a rejection");
        }
        return new ImplicitMemoryExtractionBatch(modelCandidateCount, List.of(), rejection);
    }

    public static ImplicitMemoryExtractionBatch protocolRejected() {
        return empty(MemoryExtractionResultCode.MODEL_PROTOCOL_REJECTED);
    }

    public MemoryExtractionResultCode resultCodeFor(int savedCount) {
        if (savedCount < 0 || savedCount > acceptedCandidates.size()) {
            throw new IllegalArgumentException("saved candidates exceed accepted candidates");
        }
        if (savedCount > 0) {
            return MemoryExtractionResultCode.SAVED;
        }
        return acceptedCandidates.isEmpty()
                ? emptyResultCode : MemoryExtractionResultCode.NO_CHANGE;
    }

    private static ImplicitMemoryExtractionBatch empty(MemoryExtractionResultCode code) {
        return new ImplicitMemoryExtractionBatch(0, List.of(), code);
    }

    private static void requireValidEmptyResult(
            int modelCandidateCount,
            MemoryExtractionResultCode resultCode) {
        if (resultCode == null) {
            throw new IllegalArgumentException("empty batch requires result code");
        }
        boolean valid = switch (resultCode) {
            case IGNORE, SESSION_ONLY, MODEL_EMPTY, MODEL_PROTOCOL_REJECTED ->
                    modelCandidateCount == 0;
            case ALL_REJECTED, REJECTED_SCHEMA, REJECTED_EVIDENCE,
                 REJECTED_SENSITIVE, REJECTED_STABILITY, REJECTED_CONFIDENCE,
                 REJECTED_UNSUPPORTED, REJECTED_CONTRADICTED, REJECTED_UNCERTAIN ->
                    modelCandidateCount > 0;
            case SAVED, NO_CHANGE -> false;
        };
        if (!valid) {
            throw new IllegalArgumentException("invalid empty batch result code");
        }
    }
}
