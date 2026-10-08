package com.xjjk.agent.memory.service;

import com.xjjk.agent.chat.domain.ChatTurnContext;
import com.xjjk.agent.common.api.ApiErrorCode;
import com.xjjk.agent.common.exception.BusinessException;
import com.xjjk.agent.memory.config.UserMemoryProperties;
import com.xjjk.agent.memory.domain.ExplicitMemoryCandidate;
import com.xjjk.agent.memory.domain.ExplicitMemoryCommandResult;
import com.xjjk.agent.memory.domain.ExplicitMemoryResolution;
import com.xjjk.agent.memory.observation.UserMemoryMetrics;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;

/**
 * 显式长期记忆意图的应用服务。
 *
 * <p>它位于聊天总路由的第一层：先做低成本门控，再执行安全策略、混合解析和候选校验；
 * 只有通过全部检查的事实才交给事务写服务。未命中的普通消息继续走后续意图链路。</p>
 */
@Service
public class ExplicitMemoryCommandService {

    private static final String REJECTED_TEXT = "这类内容不适合作为长期记忆保存。";
    private static final String DISABLED_TEXT = "记忆功能已关闭，可在“我的记忆”中开启。";
    private static final String CLARIFY_TEXT = "你希望我记住什么？请把需要长期记住的内容说清楚。";
    private final UserMemoryProperties properties;
    private final HybridExplicitMemoryResolver resolver;
    private final MemorySensitiveContentPolicy sensitivePolicy;
    private final ExplicitMemoryCandidateValidator validator;
    private final ExplicitMemoryWriteService writer;
    private final UserMemoryPolicyService policy;
    private final UserMemoryMetrics metrics;

    public ExplicitMemoryCommandService(
            UserMemoryProperties properties,
            HybridExplicitMemoryResolver resolver,
            MemorySensitiveContentPolicy sensitivePolicy,
            ExplicitMemoryCandidateValidator validator,
            ExplicitMemoryWriteService writer,
            UserMemoryPolicyService policy,
            UserMemoryMetrics metrics
    ) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.sensitivePolicy = Objects.requireNonNull(sensitivePolicy, "sensitivePolicy");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    public ExplicitMemoryCommandResult handle(ChatTurnContext turn, String userMessage) {
        // 第一步：全局功能关闭时不接管消息，继续交给业务查询和普通 Agent 路由。
        if (!properties.enabled()) {
            return ExplicitMemoryCommandResult.notHandled();
        }
        // 第二步：只用廉价规则判断“是否值得进一步识别”；未命中不会调用记忆模型。
        if (!resolver.mightContainExplicitMemory(userMessage)) {
            return ExplicitMemoryCommandResult.notHandled();
        }
        ExplicitMemoryResolution.Path resolutionPath = ExplicitMemoryResolution.Path.NONE;
        boolean persistenceStarted = false;
        try {
            // 第三步：功能全局可用后，还必须尊重当前用户自己的长期记忆开关。
            if (!policy.isMemoryEnabled(turn.tenantId(), turn.userId())) {
                return disabled();
            }
            // 第四步：在解析和模型调用前先拦截敏感原文，避免敏感数据进入记忆链路。
            if (!sensitivePolicy.isAllowed(userMessage)) {
                metrics.rejected("explicit_save", ApiErrorCode.MEMORY_CONTENT_REJECTED);
                metrics.explicitResolution("NONE", "POLICY_REJECTED");
                return rejected();
            }
            // 第五步：先尝试确定性快速解析，失败后才由语义模型返回 SAVE/CLARIFY/NONE。
            ExplicitMemoryResolution resolution = resolver.resolve(userMessage);
            resolutionPath = resolution.path();
            if (resolution.action() == ExplicitMemoryResolution.Action.NONE) {
                // 语义确认不是显式记忆命令时释放控制权，让消息继续进入后续路由。
                metrics.explicitResolution("NONE", "NONE");
                return ExplicitMemoryCommandResult.notHandled();
            }
            if (resolution.action() == ExplicitMemoryResolution.Action.CLARIFY) {
                // 有保存意图但事实不完整时直接追问，不允许把不确定内容写入长期记忆。
                metrics.explicitResolution(resolutionPath.name(), "CLARIFY");
                return new ExplicitMemoryCommandResult(true, false, CLARIFY_TEXT, null);
            }
            // 第六步：服务端重新校验证据、Schema、时间语义、敏感内容和保存期限；
            // 不直接信任快速解析器或模型给出的候选对象。
            ExplicitMemoryCandidate extracted = resolution.candidate();
            ExplicitMemoryCandidate candidate = validator.validate(
                    extracted, userMessage, requestsPermanentRetention(userMessage));
            persistenceStarted = true;
            // 第七步：记忆事实与索引 Outbox 在写服务的同一 MySQL 事务中提交。
            ExplicitMemoryWriteService.SaveResult saved = writer.save(turn, candidate);
            // 成功指标必须在事务真正提交后记录，不能把随后回滚的写入统计成成功。
            recordSuccessAfterCommit(resolutionPath);
            return new ExplicitMemoryCommandResult(
                    true, true, "好的，已记住：" + saved.content(), saved.memoryId());
        } catch (IllegalArgumentException rejected) {
            // 规则、证据或 Schema 拒绝属于预期业务结果，返回安全固定话术而非内部细节。
            metrics.rejected("explicit_save", ApiErrorCode.MEMORY_CONTENT_REJECTED);
            metrics.explicitResolution(resolutionPath.name(), "POLICY_REJECTED");
            return rejected();
        } catch (ExplicitMemoryExtractionException unavailable) {
            // 模型超时、调用失败和协议非法统一映射为记忆写入失败，不回退为猜测性保存。
            metrics.failure("explicit_save", ApiErrorCode.MEMORY_WRITE_FAILED);
            metrics.explicitResolution("NONE", "MODEL_FAILURE");
            throw new BusinessException(ApiErrorCode.MEMORY_WRITE_FAILED);
        } catch (UserMemoryDisabledException disabled) {
            return disabled();
        } catch (BusinessException failure) {
            metrics.failure("explicit_save", failure.errorCode());
            if (persistenceStarted) {
                metrics.explicitResolution(resolutionPath.name(), "PERSISTENCE_FAILURE");
            }
            throw failure;
        } catch (DataAccessException | TransactionException failure) {
            metrics.failure("explicit_save", ApiErrorCode.MEMORY_WRITE_FAILED);
            metrics.explicitResolution(resolutionPath.name(), "PERSISTENCE_FAILURE");
            throw new BusinessException(ApiErrorCode.MEMORY_WRITE_FAILED);
        } catch (RuntimeException failure) {
            metrics.failure("explicit_save", ApiErrorCode.INTERNAL_SERVER_ERROR);
            if (persistenceStarted) {
                metrics.explicitResolution(resolutionPath.name(), "PERSISTENCE_FAILURE");
            }
            throw failure;
        }
    }

    private static ExplicitMemoryCommandResult rejected() {
        // handled=true 表示本轮意图已被记忆模块消费，聊天主路由无需继续调用普通模型。
        return new ExplicitMemoryCommandResult(true, false, REJECTED_TEXT, null);
    }

    private ExplicitMemoryCommandResult disabled() {
        metrics.rejected("explicit_save", ApiErrorCode.MEMORY_DISABLED);
        metrics.explicitResolution("NONE", "DISABLED");
        return new ExplicitMemoryCommandResult(true, false, DISABLED_TEXT, null);
    }

    private void recordSuccessAfterCommit(ExplicitMemoryResolution.Path path) {
        // 单元测试或无事务调用可直接记录；生产写入处于事务中时必须等待 afterCommit。
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            metrics.success("explicit_save", 1);
            metrics.explicitResolution(path.name(), "SAVED");
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            private boolean committed;

            @Override
            public void afterCommit() {
                committed = true;
                metrics.success("explicit_save", 1);
                metrics.explicitResolution(path.name(), "SAVED");
            }

            @Override
            public void afterCompletion(int status) {
                // afterCompletion 只负责补记回滚失败；已经触发 afterCommit 时不能重复统计。
                if (!committed && status != STATUS_COMMITTED) {
                    metrics.failure("explicit_save", ApiErrorCode.MEMORY_WRITE_FAILED);
                }
            }
        });
    }

    private static boolean requestsPermanentRetention(String message) {
        // 永久保存必须由用户原文明确表达，模型不能自行把普通记忆升级为永久记忆。
        return ExplicitMemoryCommandDetector.requestsPermanentRetention(message);
    }
}
