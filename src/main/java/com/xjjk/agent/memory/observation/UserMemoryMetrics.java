package com.xjjk.agent.memory.observation;

import com.xjjk.agent.common.api.ApiErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Set;

/** 仅记录低基数运行状态，不接收也不记录记忆正文或证据。 */
@Component
public class UserMemoryMetrics {

    private static final Set<String> OPERATIONS = Set.of(
            "explicit_save", "edit", "delete", "clear_explicit", "clear_all",
            "auto_extract", "expiry");
    private static final Set<String> INDEX_OPERATIONS = Set.of(
            "UPSERT", "DELETE", "DELETE_EXPLICIT_SCOPE", "CLEAR_GENERATION");
    private static final Set<String> INDEX_OUTCOMES = Set.of("SUCCESS", "FAILURE");
    private static final Set<String> OUTBOX_STATES = Set.of(
            "DONE", "RETRY", "DEAD", "RECOVERED");
    private static final Set<String> RECALL_DEGRADATIONS = Set.of(
            "NONE", "KEYWORD_ONLY", "VECTOR_ONLY", "ALL_RECALL_UNAVAILABLE",
            "DISABLED", "UNAVAILABLE");
    private static final Set<String> RECALL_RESULTS = Set.of(
            "OK", "NO_CANDIDATE", "DISABLED", "UNAVAILABLE", "SKIPPED");
    private static final Set<String> CANDIDATE_STAGES = Set.of(
            "INDEX", "MYSQL_VALIDATED", "SELECTED");
    private static final Set<String> REJECTION_REASONS = Set.of(
            "OWNER_OR_STATE", "VERSION_MISMATCH", "SUPPRESSED");
    private static final Set<String> DIRECT_QUESTIONS = Set.of(
            "PREFERRED_NAME", "PROGRAMMING_LANGUAGE", "CURRENT_EMPLOYER", "WORK_SCOPE",
            "ANSWER_LANGUAGE", "ANSWER_STYLE");
    private static final Set<String> DIRECT_OUTCOMES = Set.of(
            "ANSWERED", "FALLTHROUGH", "UNAVAILABLE");
    private static final Set<String> EXPLICIT_PATHS = Set.of(
            "FAST_PATH", "SEMANTIC_PATH", "NONE");
    private static final Set<String> EXPLICIT_OUTCOMES = Set.of(
            "SAVED", "NONE", "CLARIFY", "POLICY_REJECTED", "MODEL_FAILURE",
            "PERSISTENCE_FAILURE", "DISABLED");
    private static final Set<String> EXTRACTION_DECISIONS = Set.of(
            "IGNORE", "SESSION_ONLY", "LONG_TERM");
    private static final Set<String> EXTRACTION_REJECTIONS = Set.of(
            "SCHEMA", "EVIDENCE", "SENSITIVE", "STABILITY", "CONFIDENCE",
            "UNSUPPORTED", "CONTRADICTED", "UNCERTAIN", "MULTIPLE");
    private static final Set<String> VERIFICATION_OUTCOMES = Set.of(
            "SUPPORTED", "CONTRADICTED", "UNCERTAIN");
    private final MeterRegistry registry;

    public UserMemoryMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public void success(String operation, int affectedCount) {
        record(operation, "success", "NONE");
        if (affectedCount >= 0) {
            registry.summary("agent.user.memory.affected", "operation", requireOperation(operation))
                    .record(affectedCount);
        }
    }

    public void rejected(String operation, ApiErrorCode code) {
        record(operation, "rejected", requireCode(code));
    }

    public void failure(String operation, ApiErrorCode code) {
        record(operation, "failure", requireCode(code));
    }

    public void indexOperation(String operation, String outcome) {
        registry.counter("agent.user.memory.index.operation",
                "operation", require(operation, INDEX_OPERATIONS, "索引操作"),
                "outcome", require(outcome, INDEX_OUTCOMES, "索引结果"))
                .increment();
    }

    public void outboxTransition(String status) {
        registry.counter("agent.user.memory.outbox.transition",
                "status", require(status, OUTBOX_STATES, "Outbox状态"))
                .increment();
    }

    public void recall(String degradation, String resultCode) {
        registry.counter("agent.user.memory.recall",
                "degradation", require(degradation, RECALL_DEGRADATIONS, "召回降级模式"),
                "result", require(resultCode, RECALL_RESULTS, "召回结果"))
                .increment();
    }

    public void candidateCount(String stage, int count) {
        if (count < 0) throw new IllegalArgumentException("候选数量不能为负数");
        registry.summary("agent.user.memory.recall.candidates",
                "stage", require(stage, CANDIDATE_STAGES, "候选阶段"))
                .record(count);
    }

    public void mysqlRejected(String reason, int count) {
        if (count <= 0) return;
        registry.counter("agent.user.memory.mysql.rejected",
                "reason", require(reason, REJECTION_REASONS, "MySQL拒绝原因"))
                .increment(count);
    }

    public void directAnswer(String questionType, String outcome) {
        registry.counter("agent.user.memory.direct.answer",
                "question", require(questionType, DIRECT_QUESTIONS, "直答问题类型"),
                "outcome", require(outcome, DIRECT_OUTCOMES, "直答结果"))
                .increment();
    }

    public void explicitResolution(String path, String outcome) {
        registry.counter("agent.user.memory.explicit.resolution",
                "path", require(path, EXPLICIT_PATHS, "显式记忆解析路径"),
                "outcome", require(outcome, EXPLICIT_OUTCOMES, "显式记忆解析结果"))
                .increment();
    }

    public void extractionDecision(String decision) {
        registry.counter("agent.user.memory.extraction.decision",
                "decision", require(decision, EXTRACTION_DECISIONS, "抽取决策"))
                .increment();
    }

    public void extractionRejection(String reason) {
        registry.counter("agent.user.memory.extraction.rejected",
                "reason", require(reason, EXTRACTION_REJECTIONS, "抽取拒绝原因"))
                .increment();
    }

    public void verificationOutcome(String outcome) {
        registry.counter("agent.user.memory.verification",
                "outcome", require(outcome, VERIFICATION_OUTCOMES, "证据核验结果"))
                .increment();
    }

    private void record(String operation, String outcome, String code) {
        registry.counter("agent.user.memory.operation",
                "operation", requireOperation(operation),
                "outcome", outcome,
                "code", code).increment();
    }

    private static String requireOperation(String operation) {
        if (!OPERATIONS.contains(operation)) {
            throw new IllegalArgumentException("未知用户记忆指标操作");
        }
        return operation;
    }

    private static String requireCode(ApiErrorCode code) {
        return Objects.requireNonNull(code, "code").code();
    }

    private static String require(String value, Set<String> allowed, String field) {
        if (!allowed.contains(value)) {
            throw new IllegalArgumentException("未知" + field);
        }
        return value;
    }
}
