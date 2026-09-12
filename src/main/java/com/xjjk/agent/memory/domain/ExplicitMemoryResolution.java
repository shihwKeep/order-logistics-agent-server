package com.xjjk.agent.memory.domain;

import java.util.Objects;

/** 显式记忆意图解析结果；只有 SAVE 可以携带待校验候选。 */
public record ExplicitMemoryResolution(
        Action action,
        ExplicitMemoryCandidate candidate,
        Path path,
        double confidence
) {

    public ExplicitMemoryResolution {
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(path, "path must not be null");
        if (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be finite and within [0,1]");
        }
        if (action == Action.SAVE) {
            Objects.requireNonNull(candidate, "SAVE requires a candidate");
            if (path == Path.NONE || confidence <= 0.0) {
                throw new IllegalArgumentException("SAVE requires a resolution path and positive confidence");
            }
        } else {
            if (candidate != null) {
                throw new IllegalArgumentException("Only SAVE may carry a candidate");
            }
            if (action == Action.NONE && path != Path.NONE) {
                throw new IllegalArgumentException("NONE action requires NONE path");
            }
            if (action == Action.CLARIFY && path == Path.NONE) {
                throw new IllegalArgumentException("CLARIFY requires a resolution path");
            }
        }
    }

    public static ExplicitMemoryResolution save(
            ExplicitMemoryCandidate candidate, Path path, double confidence) {
        return new ExplicitMemoryResolution(Action.SAVE, candidate, path, confidence);
    }

    public static ExplicitMemoryResolution clarify(Path path) {
        return new ExplicitMemoryResolution(Action.CLARIFY, null, path, 0.0);
    }

    public static ExplicitMemoryResolution none() {
        return new ExplicitMemoryResolution(Action.NONE, null, Path.NONE, 0.0);
    }

    public enum Action {
        SAVE,
        CLARIFY,
        NONE
    }

    public enum Path {
        FAST_PATH,
        SEMANTIC_PATH,
        NONE
    }
}
