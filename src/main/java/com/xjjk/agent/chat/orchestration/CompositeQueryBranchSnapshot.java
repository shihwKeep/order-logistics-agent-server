package com.xjjk.agent.chat.orchestration;

import java.util.Objects;

/** 可写入 checkpoint 的脱敏分支结果摘要。 */
public record CompositeQueryBranchSnapshot(
        String branchName,
        String resultKind,
        String status,
        String safeSummary,
        String failureSummary,
        int retryCount) implements java.io.Serializable {

    public CompositeQueryBranchSnapshot {
        branchName = requireText(branchName, "分支名称不能为空");
        resultKind = requireText(resultKind, "结果类型不能为空");
        status = requireText(status, "分支状态不能为空");
        safeSummary = safeSummary == null ? "" : safeSummary.strip();
        failureSummary = failureSummary == null ? "" : failureSummary.strip();
        if (retryCount < 0) {
            throw new IllegalArgumentException("分支重试次数不能为负数");
        }
    }

    private static String requireText(String value, String message) {
        Objects.requireNonNull(value, message);
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.codePoints()
                .anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(message);
        }
        return normalized;
    }
}
