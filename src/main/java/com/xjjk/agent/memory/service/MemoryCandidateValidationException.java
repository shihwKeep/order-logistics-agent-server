package com.xjjk.agent.memory.service;

/** 不携带用户正文的稳定候选拒绝。 */
public final class MemoryCandidateValidationException extends IllegalArgumentException {

    private final Reason reason;

    public MemoryCandidateValidationException(Reason reason) {
        super("MEMORY_CANDIDATE_REJECTED_" + reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        SCHEMA,
        EVIDENCE,
        SENSITIVE,
        STABILITY,
        CONFIDENCE,
        UNSUPPORTED
    }
}
