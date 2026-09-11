package com.xjjk.agent.memory.domain;

import java.util.List;
import java.util.Objects;

/** 将模型候选数量与已经确定性校验的候选绑定，供事务提交时生成安全结果码。 */
public record ImplicitMemoryExtractionBatch(
        int modelCandidateCount,
        List<ImplicitMemoryCandidate> acceptedCandidates,
        MemoryExtractionResultCode emptyResultCode
) {

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
            List<ImplicitMemoryCandidate> acceptedCandidates
    ) {
        Objects.requireNonNull(acceptedCandidates, "acceptedCandidates");
        List<ImplicitMemoryCandidate> copy = List.copyOf(acceptedCandidates);
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

    public static ImplicitMemoryExtractionBatch protocolRejected() {
        return new ImplicitMemoryExtractionBatch(
                0, List.of(), MemoryExtractionResultCode.MODEL_PROTOCOL_REJECTED);
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

    private static void requireValidEmptyResult(
            int modelCandidateCount,
            MemoryExtractionResultCode resultCode
    ) {
        if (resultCode == null) {
            throw new IllegalArgumentException("empty batch requires result code");
        }
        boolean valid = switch (resultCode) {
            case MODEL_EMPTY, MODEL_PROTOCOL_REJECTED -> modelCandidateCount == 0;
            case ALL_REJECTED -> modelCandidateCount > 0;
            case SAVED, NO_CHANGE -> false;
        };
        if (!valid) {
            throw new IllegalArgumentException("invalid empty batch result code");
        }
    }
}
