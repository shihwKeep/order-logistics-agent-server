package com.xjjk.agent.memory.service;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 显式记忆命令的确定性前缀检测器。
 *
 * <p>该类只提取受控命令后的正文和“是否永久”标志，不理解开放自然语言语义；
 * 未命中的表达仍可由 {@link ExplicitMemoryCandidateGate} 进入语义模型兜底。</p>
 */
public class ExplicitMemoryCommandDetector {

    // 永久保存是高风险生命周期决策，只允许命中句首的明确表达。
    private static final Pattern PERMANENT_COMMAND_PREFIX = Pattern.compile(
            "^(?:请\\s*)?(?:帮我\\s*)?(?:永久|永远|始终|一直|长期)\\s*(?:记住|保存|保留)");
    private static final List<String> NORMAL_PREFIXES = List.of("请记住", "帮我记住", "记住", "以后请", "以后都");
    private static final List<String> MIXED_INTENT_MARKERS = List.of("顺便", "另外帮我", "同时帮我");

    private final int maxPayloadCodePoints;

    public ExplicitMemoryCommandDetector(int maxPayloadCodePoints) {
        if (maxPayloadCodePoints < 1) {
            throw new IllegalArgumentException("maxPayloadCodePoints must be positive");
        }
        this.maxPayloadCodePoints = maxPayloadCodePoints;
    }

    public Optional<CommandText> detect(String input) {
        // 第一步：空输入直接不命中，避免后续规范化出现空指针。
        if (input == null) {
            return Optional.empty();
        }
        // 第二步：折叠不同空白字符，保证中文空格差异不会改变命令识别结果。
        String normalized = normalizeWhitespace(input);
        // 第三步：永久命令优先于普通命令，避免生命周期信息在普通前缀中丢失。
        Optional<CommandText> permanent = detectPermanent(normalized);
        return permanent.isPresent() ? permanent : detect(normalized, NORMAL_PREFIXES, false);
    }

    static boolean requestsPermanentRetention(String input) {
        // 写入前再次从原文确认永久意图，不能依赖模型返回的 retention 字段。
        return input != null && PERMANENT_COMMAND_PREFIX
                .matcher(normalizeWhitespace(input)).find();
    }

    private Optional<CommandText> detectPermanent(String input) {
        Matcher matcher = PERMANENT_COMMAND_PREFIX.matcher(input);
        if (!matcher.find()) {
            return Optional.empty();
        }
        return command(input.substring(matcher.end()), true);
    }

    private Optional<CommandText> detect(String input, List<String> prefixes, boolean permanent) {
        // 普通命令使用严格句首匹配；正文中偶然出现“记住”不直接进入快速路径。
        for (String prefix : prefixes) {
            if (!input.startsWith(prefix)) {
                continue;
            }
            return command(input.substring(prefix.length()), permanent);
        }
        return Optional.empty();
    }

    private Optional<CommandText> command(String rawPayload, boolean permanent) {
        // 去除命令与正文之间的外围标点，再检查正文完整性和长度上限。
        String payload = stripOuterPunctuation(rawPayload);
        if (payload.isBlank()
                || payload.codePointCount(0, payload.length()) > maxPayloadCodePoints
                || MIXED_INTENT_MARKERS.stream().anyMatch(payload::contains)) {
            // 混合意图可能同时包含保存和业务操作，快速路径无法安全拆分，交给后续语义判断。
            return Optional.empty();
        }
        return Optional.of(new CommandText(payload, permanent));
    }

    static String normalizeWhitespace(String value) {
        // 按 Unicode 码点处理空白，连续空白折叠为一个普通空格并清理首尾。
        StringBuilder result = new StringBuilder(value.length());
        boolean previousWhitespace = true;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            boolean whitespace = Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
            if (whitespace) {
                if (!previousWhitespace) {
                    result.append(' ');
                }
            } else {
                result.appendCodePoint(codePoint);
            }
            previousWhitespace = whitespace;
        }
        return result.toString().strip();
    }

    private static String stripOuterPunctuation(String value) {
        // 只清理正文首尾标点，不修改正文内部内容，确保后续证据仍能对应用户原文。
        String normalized = normalizeWhitespace(value);
        int start = 0;
        int end = normalized.length();
        while (start < end) {
            int codePoint = normalized.codePointAt(start);
            if (!isOuterPunctuation(codePoint)) {
                break;
            }
            start += Character.charCount(codePoint);
        }
        while (start < end) {
            int codePoint = normalized.codePointBefore(end);
            if (!isOuterPunctuation(codePoint)) {
                break;
            }
            end -= Character.charCount(codePoint);
        }
        return normalized.substring(start, end).strip();
    }

    private static boolean isOuterPunctuation(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.CONNECTOR_PUNCTUATION
                || type == Character.DASH_PUNCTUATION
                || type == Character.START_PUNCTUATION
                || type == Character.END_PUNCTUATION
                || type == Character.INITIAL_QUOTE_PUNCTUATION
                || type == Character.FINAL_QUOTE_PUNCTUATION
                || type == Character.OTHER_PUNCTUATION;
    }

    public record CommandText(String payload, boolean permanent) {
        // payload 是去掉命令前缀后的原始事实文本；permanent 只能来自确定性前缀检测。
    }
}
