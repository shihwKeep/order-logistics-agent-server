package com.xjjk.agent.chat.result;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xjjk.agent.chat.config.ChatResultProperties;
import com.xjjk.agent.tool.ToolUiResult;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Pattern;

/** 在 SSE 发布前把工具结果转换为可持久化的有界 JSON 快照。 */
@Component
public class ChatToolResultRecorder {

    /** 工具名和结果类型最终会进入数据库列及 SSE 协议，必须使用有限安全字符集。 */
    private static final int MAX_TOOL_NAME_LENGTH = 64;
    private static final int MAX_KIND_LENGTH = 32;
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_-]+");

    private final ObjectMapper objectMapper;
    private final ChatResultProperties properties;

    public ChatToolResultRecorder(
            ObjectMapper objectMapper,
            ChatResultProperties properties) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        this.properties = Objects.requireNonNull(properties, "结果配置不能为空");
    }

    /**
     * 按固定顺序执行校验和序列化，调用成功后结果才允许进入 SSE 发布通道。
     * 这样不会出现“客户端已经看到卡片，但服务端后来才发现无法保存”的分叉。
     */
    public PendingMessageResult prepare(ToolUiResult result, int resultSequence) {
        if (!properties.enabled()) {
            throw new IllegalStateException("结构化工具结果持久化未启用");
        }
        validate(result, resultSequence);

        String payloadJson;
        try {
            // 只序列化 data，外围协议字段由服务端单独保存，前端不能用载荷覆盖它们。
            payloadJson = objectMapper.writeValueAsString(result.data());
        } catch (JsonProcessingException | RuntimeException exception) {
            // Jackson 异常可能携带业务对象路径或正文，跨边界时只暴露固定安全语义。
            throw new ToolResultSerializationException();
        }

        // 长度按 UTF-8 字节计算，与数据库及网络真实占用口径保持一致。
        int payloadBytes = payloadJson.getBytes(StandardCharsets.UTF_8).length;
        if (payloadBytes > properties.maxPayloadBytes()) {
            throw new ToolResultTooLargeException(
                    payloadBytes, properties.maxPayloadBytes());
        }
        return new PendingMessageResult(
                resultSequence,
                result.toolName(),
                result.kind(),
                result.schemaVersion(),
                payloadJson,
                payloadBytes,
                result.queriedAt());
    }

    /** 在 JSON 序列化前验证外围协议，拒绝不可持久化或无法审计的结果。 */
    private void validate(ToolUiResult result, int resultSequence) {
        if (result == null) {
            throw new IllegalArgumentException("工具结果不能为空");
        }
        if (resultSequence <= 0) {
            throw new IllegalArgumentException("结构化结果序号必须从1开始");
        }
        validateName(result.toolName(), MAX_TOOL_NAME_LENGTH, "工具名称");
        validateName(result.kind(), MAX_KIND_LENGTH, "结果类型");
        if (result.schemaVersion() <= 0) {
            throw new IllegalArgumentException("结构化结果版本必须大于0");
        }
        if (result.queriedAt() == null) {
            throw new IllegalArgumentException("业务查询时间不能为空");
        }
        if (result.data() == null) {
            throw new IllegalArgumentException("结构化结果数据不能为空");
        }
    }

    /** 工具名和 kind 只允许稳定协议字符，禁止空白、控制符和路径式名称。 */
    private void validateName(String value, int maxLength, String fieldName) {
        if (value == null || value.isBlank() || value.length() > maxLength
                || !SAFE_NAME.matcher(value).matches()) {
            throw new IllegalArgumentException(fieldName + "不合法");
        }
    }
}
