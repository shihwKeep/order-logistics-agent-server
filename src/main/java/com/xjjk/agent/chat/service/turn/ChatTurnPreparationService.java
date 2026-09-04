package com.xjjk.agent.chat.service.turn;

import com.xjjk.agent.chat.domain.ChatTurnContext;
import com.xjjk.agent.chat.service.conversation.AgentConversationService;
import com.xjjk.agent.common.api.ApiErrorCode;
import com.xjjk.agent.common.exception.BusinessException;
import com.xjjk.agent.identity.domain.AgentIdentity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Objects;

/**
 * 聊天请求准备服务。
 *
 * 负责选择或创建会话、恢复过期占用、开始本轮问答。
 * 不调用模型，不持有覆盖整个流程的大事务。
 */
@Service
@RequiredArgsConstructor
public class ChatTurnPreparationService {

    private final AgentConversationService conversationService;
    private final ChatTurnRecoveryService recoveryService;
    private final ChatTurnStartService startService;

    /**
     * 准备本轮问答。
     *
     * @param conversationId 已有会话 ID；未提供时创建新会话
     * @param identity 后端校验后的认证身份
     * @param userText 当前用户的问题
     * @return 已完成开始事务的本轮上下文
     */
    public ChatTurnContext prepare(
            String conversationId,
            AgentIdentity identity,
            String userText
    ) {
        Objects.requireNonNull(identity, "认证身份不能为空");

        // 在创建会话之前校验，避免无效输入产生空会话。
        if (!StringUtils.hasText(userText)
                || userText.length() > 2000
                || (conversationId != null && conversationId.length() > 64)) {
            throw new BusinessException(ApiErrorCode.VALIDATION_ERROR);
        }

        String resolvedConversationId;

        if (!StringUtils.hasText(conversationId)) {
            // 新会话的租户、用户和组织归属均来自认证身份。
            resolvedConversationId = conversationService
                    .create(identity)
                    .getConversationId();
        } else {
            resolvedConversationId = conversationId;

            // 恢复内部也会校验归属；不存在或无权访问时直接抛异常。
            // 返回 false 不代表可以开始，后面的 begin 会再次检查占用。
            recoveryService.recoverExpired(
                    resolvedConversationId,
                    identity
            );
        }

        return startService.begin(
                resolvedConversationId,
                identity,
                userText
        );
    }
}
