package com.xjjk.agent.chat.api.controller;

import com.xjjk.agent.chat.api.dto.ChatMessagePageResponse;
import com.xjjk.agent.chat.api.dto.ConversationResponse;
import com.xjjk.agent.chat.api.dto.ConversationPageResponse;
import com.xjjk.agent.chat.persistence.entity.AgentConversationEntity;
import com.xjjk.agent.chat.service.conversation.AgentConversationService;
import com.xjjk.agent.chat.service.conversation.AgentConversationListService;
import com.xjjk.agent.chat.service.conversation.AgentMessageQueryService;
import com.xjjk.agent.common.api.ApiResponse;
import com.xjjk.agent.identity.domain.AgentIdentity;
import com.xjjk.agent.identity.web.CurrentAgentIdentity;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Agent 会话管理接口。
 *
 * 用户身份由现有认证拦截器校验，
 * 不接受前端指定租户、用户或组织归属。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/conversations")
public class AgentConversationController {

    private final AgentConversationService conversationService;

    private final AgentConversationListService conversationListService;

    /** 历史消息查询服务。 */
    private final AgentMessageQueryService messageQueryService;

    /** 查询当前用户最近活动的非空会话。 */
    @GetMapping
    public ApiResponse<ConversationPageResponse> list(
            @CurrentAgentIdentity AgentIdentity identity,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "10") int pageSize
    ) {
        return ApiResponse.success(
                conversationListService.list(identity, cursor, pageSize));
    }

    /**
     * 创建当前用户的新会话。
     * 不需要请求体，每次调用都会创建一个独立会话。
     */
    @PostMapping
    public ApiResponse<ConversationResponse> create(
            @CurrentAgentIdentity AgentIdentity identity
    ) {
        AgentConversationEntity conversation =
                conversationService.create(identity);

        return ApiResponse.success(toResponse(conversation));
    }

    /**
     * 查询当前用户拥有的指定会话。
     * 会话不存在或归属不匹配时，统一返回会话不可访问错误。
     */
    @GetMapping("/{conversationId}")
    public ApiResponse<ConversationResponse> get(
            @PathVariable("conversationId") String conversationId,
            @CurrentAgentIdentity AgentIdentity identity
    ) {
        AgentConversationEntity conversation =
                conversationService.requireOwned(conversationId, identity);

        return ApiResponse.success(toResponse(conversation));
    }

    /**
     * 分页查询当前用户的会话消息。
     *
     * 不传游标时返回最新一页；
     * 传入游标时返回该序号之前的消息。
     * 每页消息均按照序号从小到大排列。
     */
    @GetMapping("/{conversationId}/messages")
    public ApiResponse<ChatMessagePageResponse> messages(
            @PathVariable("conversationId") String conversationId,
            @CurrentAgentIdentity AgentIdentity identity,
            @RequestParam(
                    name = "beforeSequence",
                    required = false
            ) Long beforeSequence,
            @RequestParam(
                    name = "pageSize",
                    defaultValue = "20"
            ) int pageSize
    ) {
        ChatMessagePageResponse page = messageQueryService.queryPage(
                conversationId,
                identity,
                beforeSequence,
                pageSize
        );

        return ApiResponse.success(page);
    }

    /** 将数据库实体转换为允许对外返回的会话信息。 */
    private ConversationResponse toResponse(
            AgentConversationEntity conversation
    ) {
        return new ConversationResponse(
                conversation.getConversationId(),
                conversation.getTitle()
        );
    }
}
