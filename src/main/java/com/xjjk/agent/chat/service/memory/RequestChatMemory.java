package com.xjjk.agent.chat.service.memory;

import com.xjjk.agent.chat.domain.memory.ChatContextSelection;
import com.xjjk.agent.chat.domain.memory.ChatHistoryTurn;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 单次模型请求使用的临时聊天记忆。
 *
 * 初始历史来自已经完成权限检查和 Token 预算筛选的结果。
 * 框架新增的用户消息、助手消息仅保存在本对象中，
 * 不写入 MySQL 或 Redis，不替代业务消息的收尾事务。
 *
 * 每次订阅创建新实例，不作为单例 Bean，不跨请求复用。
 */
public final class RequestChatMemory implements ChatMemory {

    /** 本对象绑定的业务会话 ID。 */
    private final String conversationId;

    /** 本次请求的临时消息，按时间从旧到新排列。 */
    private final List<Message> messages = new ArrayList<>();

    /**
     * 使用已经筛选好的摘要和完整历史轮次初始化记忆。
     *
     * 不加入系统提示词和当前问题：
     * 系统提示词由 ChatClient 配置；
     * 当前问题通过 user(...) 传入，由 Advisor 追加到记忆。
     */
    public RequestChatMemory(ChatContextSelection selection) {
        Objects.requireNonNull(selection, "上下文筛选结果不能为空");

        this.conversationId = selection.source().conversationId();

        if (selection.selectedSummary() != null) {
            // 摘要属于不可信历史数据，必须保持 USER 低权限且先于原文轮次。
            messages.add(new UserMessage(selection.selectedSummary()));
        }

        if (selection.selectedBusinessReference() != null) {
            // 业务引用只用于指代消解，同样保持 USER 低权限并放在原始历史之前。
            messages.add(new UserMessage(selection.selectedBusinessReference()));
        }

        if (selection.selectedUserMemoryContext() != null) {
            // 跨会话记忆保持 USER 低权限，并位于会话原文之前。
            messages.add(new UserMessage(selection.selectedUserMemoryContext()));
        }

        for (ChatHistoryTurn turn : selection.selectedTurns()) {
            messages.add(new UserMessage(turn.userContent()));
            messages.add(new AssistantMessage(turn.assistantContent()));
        }
    }

    /**
     * 追加本次请求产生的消息。
     *
     * 只更新临时内存，不判断业务是否成功，也不执行持久化。
     * 使用 synchronized，避免框架切换执行线程时发生并发读写。
     */
    @Override
    public synchronized void add(
            String conversationId,
            List<Message> newMessages
    ) {
        checkConversationId(conversationId);
        Objects.requireNonNull(newMessages, "新增消息列表不能为空");

        // 先复制并校验全部元素，再修改内部列表，避免部分追加。
        List<Message> copiedMessages = List.copyOf(newMessages);
        messages.addAll(copiedMessages);
    }

    /**
     * 返回当前临时记忆的列表快照。
     *
     * 不直接暴露内部可变列表。
     */
    @Override
    public synchronized List<Message> get(String conversationId) {
        checkConversationId(conversationId);
        return List.copyOf(messages);
    }

    /**
     * 清空本次请求的临时记忆。
     *
     * 不删除数据库中的会话和消息。
     */
    @Override
    public synchronized void clear(String conversationId) {
        checkConversationId(conversationId);
        messages.clear();
    }

    /**
     * 防止调用方把其他会话 ID 传给本对象。
     *
     * 这里只检查绑定关系，不替代上游的租户和用户权限校验。
     */
    private void checkConversationId(String conversationId) {
        if (!this.conversationId.equals(conversationId)) {
            throw new IllegalArgumentException(
                    "会话 ID 与本次请求记忆不一致"
            );
        }
    }
}
