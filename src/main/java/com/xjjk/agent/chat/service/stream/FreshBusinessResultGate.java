package com.xjjk.agent.chat.service.stream;

import com.xjjk.agent.chat.routing.BusinessQueryMode;
import com.xjjk.agent.chat.stream.ChatSseSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** 校验模型实时查询是否产生了本轮匹配的结构化结果，并原子刷新输出。 */
@Slf4j
@Component
public class FreshBusinessResultGate {

    static final String MISSING_RESULT_MESSAGE =
            "本轮未完成实时业务查询，请补充查询条件或稍后重试。";

    void flush(ChatTurnExecution execution, ChatSseSession session) throws IOException {
        if (execution.queryPlan.mode() != BusinessQueryMode.MODEL_REQUIRED) {
            return;
        }
        List<StagedToolResult> staged = execution.stagedResultSnapshot();
        Set<String> actualKinds = staged.stream()
                .map(value -> value.uiResult().kind())
                .collect(Collectors.toUnmodifiableSet());
        boolean accepted = !staged.isEmpty()
                && execution.queryPlan.acceptedResultKinds().containsAll(actualKinds);
        if (!accepted) {
            execution.discardStagedResults();
            execution.replaceContent(MISSING_RESULT_MESSAGE);
            session.delta(MISSING_RESULT_MESSAGE);
            execution.metrics.markFirstDeltaSent();
            logOutcome(
                    execution,
                    actualKinds,
                    staged.isEmpty() ? "MISSING_RESULT" : "UNEXPECTED_RESULT");
            return;
        }

        // 先提升为可持久化结果，再按产生顺序发布；发送失败时沿用现有 OUTPUT_ERROR 收尾。
        execution.promoteStagedResults();
        for (StagedToolResult value : staged) {
            session.result(value.uiResult());
        }
        String text = execution.content.toString();
        if (!text.isEmpty()) {
            session.delta(text);
            execution.metrics.markFirstDeltaSent();
        }
        logOutcome(execution, actualKinds, "FRESH_RESULT_ACCEPTED");
    }

    private void logOutcome(
            ChatTurnExecution execution,
            Set<String> actualKinds,
            String outcome) {
        log.info(
                "chat_business_query_gate requestId={}, mode={}, expectedKinds={}, actualKinds={}, outcome={}",
                execution.requestId,
                execution.queryPlan.mode(),
                execution.queryPlan.acceptedResultKinds(),
                actualKinds,
                outcome);
    }
}
