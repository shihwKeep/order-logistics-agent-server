package com.xjjk.agent.memory.service;

import com.xjjk.agent.chat.persistence.entity.AgentMessageEntity;
import com.xjjk.agent.chat.persistence.mapper.AgentMessageMapper;
import com.xjjk.agent.memory.domain.ImplicitMemoryCandidate;
import com.xjjk.agent.memory.domain.MemoryExtractionTaskClaim;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
public class ImplicitMemoryTaskWorker {

    private final AgentMessageMapper messageMapper;
    private final ImplicitMemoryModelClient modelClient;
    private final ImplicitMemoryCandidateValidator validator;
    private final ImplicitMemoryCommitService commitService;
    private final ImplicitMemoryTaskCommitService taskState;

    public ImplicitMemoryTaskWorker(
            AgentMessageMapper messageMapper,
            ImplicitMemoryModelClient modelClient,
            ImplicitMemoryCandidateValidator validator,
            ImplicitMemoryCommitService commitService,
            ImplicitMemoryTaskCommitService taskState
    ) {
        this.messageMapper = Objects.requireNonNull(messageMapper, "messageMapper");
        this.modelClient = Objects.requireNonNull(modelClient, "modelClient");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.commitService = Objects.requireNonNull(commitService, "commitService");
        this.taskState = Objects.requireNonNull(taskState, "taskState");
    }

    public void process(MemoryExtractionTaskClaim claim) {
        AgentMessageEntity source = messageMapper.selectOwnedSuccessfulUserMessage(
                claim.tenantId(), claim.userId(), claim.conversationId(), claim.requestId(),
                claim.userMessageId(), claim.userMessageSequence());
        if (source == null || source.getContent() == null || source.getContent().isBlank()) {
            taskState.cancel(claim, "SOURCE_NOT_AVAILABLE");
            return;
        }
        String prior = messageMapper.selectPreviousSuccessfulUserContent(
                claim.tenantId(), claim.userId(), claim.conversationId(),
                claim.userMessageSequence());
        try {
            List<ImplicitMemoryCandidate> extracted = modelClient.extract(
                    new ImplicitMemoryModelClient.Request(
                            claim.requestId(), source.getContent(), prior));
            List<ImplicitMemoryCandidate> accepted = new ArrayList<>();
            for (ImplicitMemoryCandidate candidate : extracted) {
                try {
                    accepted.add(validator.validate(candidate, source.getContent()));
                } catch (IllegalArgumentException rejected) {
                    // 单个候选按封闭策略丢弃，不记录正文或模型输出。
                }
            }
            commitService.commit(claim, List.copyOf(accepted));
        } catch (ImplicitMemoryExtractionException error) {
            if (error.code() == ImplicitMemoryExtractionException.Code.MODEL_PROTOCOL_ERROR) {
                commitService.commit(claim, List.of());
            } else {
                taskState.scheduleRetry(claim, error.code().name());
            }
        } catch (RuntimeException error) {
            log.warn("implicit_memory_task taskId={}, conversationId={}, result=RETRY, errorCode=WORKER_FAILED",
                    claim.taskId(), claim.conversationId());
            taskState.scheduleRetry(claim, "WORKER_FAILED");
        }
    }
}
