package com.xjjk.agent.chat.service.memory;

import com.xjjk.agent.chat.domain.MessageRole;
import com.xjjk.agent.chat.domain.MessageStatus;
import com.xjjk.agent.chat.persistence.projection.ChatHistoryMessageMetadata;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 历史上下文候选消息筛选器。
 *
 * 只处理元信息，不访问外部系统。
 * 当前只支持普通文本的 USER、ASSISTANT 问答结构。
 */
@Component
public class ChatHistorySelector {

    /**
     * 从最近的完整成功轮次开始，按正文读取预算选择消息。
     *
     * @param candidates 已限定归属和历史边界的候选消息；
     *                   必须按消息序号严格倒序，且已去掉额外探测行
     * @param maxReadBytes 正文读取总字节预算
     * @return 选中的消息 ID、正文总字节数及预算截断标记
     */
    public Selection select(
            List<ChatHistoryMessageMetadata> candidates,
            long maxReadBytes
    ) {
        Objects.requireNonNull(candidates, "历史候选消息不能为空");

        if (maxReadBytes <= 0) {
            throw new IllegalArgumentException("历史正文预算必须大于 0");
        }

        // 保留首次出现的顺序，完整轮次将按从新到旧的顺序处理。
        Map<String, List<ChatHistoryMessageMetadata>> groups =
                new LinkedHashMap<>();

        Long previousSequence = null;

        for (ChatHistoryMessageMetadata message : candidates) {
            validateMetadata(message);

            if (previousSequence != null
                    && message.getMessageSequence() >= previousSequence) {
                throw new IllegalArgumentException("候选消息必须按序号严格倒序");
            }

            previousSequence = message.getMessageSequence();

            groups.computeIfAbsent(
                    message.getRequestId(),
                    ignored -> new ArrayList<>(2)
            ).add(message);
        }

        List<List<String>> selectedTurns = new ArrayList<>();
        long selectedBytes = 0L;
        boolean readBudgetTruncated = false;

        for (List<ChatHistoryMessageMetadata> group : groups.values()) {
            // 候选范围的最早边界可能截断一个轮次，不使用不完整轮次。
            if (group.size() == 1) {
                continue;
            }

            if (group.size() != 2) {
                throw new IllegalStateException("普通文本历史轮次消息数量异常");
            }

            // 输入为倒序，当前文本问答结构应是助手在前、用户在后。
            ChatHistoryMessageMetadata assistant = group.get(0);
            ChatHistoryMessageMetadata user = group.get(1);

            if (!MessageRole.ASSISTANT.name().equals(assistant.getRole())
                    || !MessageRole.USER.name().equals(user.getRole())
                    || assistant.getMessageSequence()
                    != Math.addExact(user.getMessageSequence(), 1L)) {
                throw new IllegalStateException("普通文本历史轮次结构异常");
            }

            // 用户消息保存成功，不代表整轮回答成功。
            if (!MessageStatus.SUCCESS.name().equals(user.getStatus())
                    || !MessageStatus.SUCCESS.name().equals(
                    assistant.getStatus())) {
                continue;
            }

            if (user.getContentBytes() == 0
                    || assistant.getContentBytes() == 0) {
                throw new IllegalStateException("成功历史消息的正文长度异常");
            }

            long turnBytes = Math.addExact(
                    user.getContentBytes(),
                    assistant.getContentBytes()
            );

            // selectedBytes 始终不大于预算，使用减法避免累加溢出。
            if (turnBytes > maxReadBytes - selectedBytes) {
                readBudgetTruncated = true;
                break;
            }

            selectedTurns.add(List.of(
                    user.getMessageId(),
                    assistant.getMessageId()
            ));

            selectedBytes += turnBytes;
        }

        // 恢复从旧到新的顺序，每个轮次内部保持用户在前、助手在后。
        Collections.reverse(selectedTurns);

        List<String> messageIds = selectedTurns.stream()
                .flatMap(List::stream)
                .toList();

        return new Selection(
                messageIds,
                selectedBytes,
                readBudgetTruncated
        );
    }

    /**
     * 检查数据库查询结果的必要字段。
     * 结构异常不能伪装成正常的空历史。
     */
    private void validateMetadata(ChatHistoryMessageMetadata message) {
        if (message == null
                || !StringUtils.hasText(message.getMessageId())
                || !StringUtils.hasText(message.getRequestId())
                || !StringUtils.hasText(message.getStatus())
                || message.getMessageSequence() == null
                || message.getMessageSequence() < 1
                || message.getContentBytes() == null
                || message.getContentBytes() < 0) {
            throw new IllegalStateException("历史消息元信息不完整");
        }

        if (!MessageRole.USER.name().equals(message.getRole())
                && !MessageRole.ASSISTANT.name().equals(message.getRole())) {
            throw new IllegalStateException("历史消息包含尚未支持的角色");
        }
    }

    /**
     * 历史正文读取计划，不包含正文。
     *
     * @param messageIds 待读取的消息 ID，按消息序号升序排列
     * @param contentBytes 选中消息的正文总字节数
     * @param readBudgetTruncated 是否因正文读取预算不足而停止选择
     */
    public record Selection(
            List<String> messageIds,
            long contentBytes,
            boolean readBudgetTruncated
    ) {
        public Selection {
            messageIds = List.copyOf(messageIds);

            if (contentBytes < 0) {
                throw new IllegalArgumentException("正文总字节数不能为负数");
            }
        }
    }
}
