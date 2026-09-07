package com.xjjk.agent.chat.service.summary;

import com.xjjk.agent.chat.domain.summary.ChatSummaryContent;
import com.xjjk.agent.chat.domain.summary.ChatSummaryEntity;
import com.xjjk.agent.chat.domain.summary.ChatSummaryFact;
import com.xjjk.agent.chat.domain.summary.ChatSummaryItem;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 摘要输入和输出的确定性敏感信息脱敏器。
 *
 * 不记录命中内容；同一规则同时用于模型调用前和持久化前，降低凭据进入长期记忆的风险。
 */
@Component
public class SensitiveContentSanitizer {

    private static final String SECRET_NAME =
            "(?:password|pwd|client[_-]?secret|api[_-]?key|"
                    + "access[_-]?token|refresh[_-]?token)";

    private static final Pattern BEARER_TOKEN = Pattern.compile(
            "(?i)(\\bBearer\\s+)[A-Za-z0-9._~+/=-]+"
    );
    private static final Pattern BASIC_AUTH = Pattern.compile(
            "(?i)(\\bBasic\\s+)[A-Za-z0-9+/=_-]+"
    );
    private static final Pattern JWT_TOKEN = Pattern.compile(
            "\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\b"
    );
    private static final Pattern NAMED_SECRET_PREFIX = Pattern.compile(
            "(?i)[\\\"']?" + SECRET_NAME
                    + "[\\\"']?\\s*[:=]\\s*"
    );
    private static final Pattern PRIVATE_KEY = Pattern.compile(
            "(?s)-----BEGIN [A-Z ]*PRIVATE KEY-----.*?"
                    + "-----END [A-Z ]*PRIVATE KEY-----"
    );
    private static final Pattern HIGH_ENTROPY_SECRET = Pattern.compile(
            "(?<![A-Za-z0-9])(?:sk-[A-Za-z0-9_-]{16,}|"
                    + "[A-Fa-f0-9]{40,}|[A-Za-z0-9+/=_-]{48,})"
                    + "(?![A-Za-z0-9])"
    );

    /** 对一段文本执行不改变业务结构的确定性脱敏。 */
    public String sanitize(String input) {
        Objects.requireNonNull(input, "待脱敏文本不能为空");
        String result = BEARER_TOKEN.matcher(input)
                .replaceAll("$1[REDACTED_TOKEN]");
        result = BASIC_AUTH.matcher(result)
                .replaceAll("$1[REDACTED_TOKEN]");
        result = JWT_TOKEN.matcher(result)
                .replaceAll("[REDACTED_TOKEN]");
        result = sanitizeNamedSecrets(result);
        result = PRIVATE_KEY.matcher(result)
                .replaceAll("[REDACTED_PRIVATE_KEY]");
        result = HIGH_ENTROPY_SECRET.matcher(result)
                .replaceAll("[REDACTED_SECRET]");
        if (containsSensitiveValue(result)) {
            // 规则新增或组合替换出现遗漏时拒绝输出，不能把残余凭据带入模型或摘要表。
            throw new IllegalStateException("敏感信息脱敏后仍命中凭据规则");
        }
        return result;
    }

    private static boolean containsSensitiveValue(String value) {
        return BEARER_TOKEN.matcher(value).find()
                || BASIC_AUTH.matcher(value).find()
                || JWT_TOKEN.matcher(value).find()
                || containsUnredactedNamedSecret(value)
                || PRIVATE_KEY.matcher(value).find()
                || HIGH_ENTROPY_SECRET.matcher(value).find();
    }

    /**
     * 命名凭据的值采用单次线性扫描，不用可回溯的正则解析任意用户正文。
     * 引号未闭合时直接脱敏到文本末尾，避免残值泄露和正则拒绝服务。
     */
    private static String sanitizeNamedSecrets(String input) {
        Matcher matcher = NAMED_SECRET_PREFIX.matcher(input);
        StringBuilder sanitized = new StringBuilder(input.length());
        int copiedUntil = 0;
        int searchFrom = 0;
        while (matcher.find(searchFrom)) {
            sanitized.append(input, copiedUntil, matcher.end());
            int valueStart = matcher.end();
            if (valueStart >= input.length()) {
                copiedUntil = valueStart;
                break;
            }
            char first = input.charAt(valueStart);
            if (first == '\"' || first == '\'') {
                sanitized.append(first).append("[REDACTED_SECRET]");
                int closingQuote = findClosingQuote(
                        input,
                        valueStart + 1,
                        first
                );
                if (closingQuote < 0) {
                    // 无闭合引号时剩余文本都可能属于凭据值，安全优先，全部丢弃。
                    sanitized.append(first);
                    copiedUntil = input.length();
                    searchFrom = input.length();
                    break;
                }
                sanitized.append(first);
                copiedUntil = closingQuote + 1;
                searchFrom = copiedUntil;
                continue;
            }

            int valueEnd = findUnquotedValueEnd(input, valueStart);
            sanitized.append("[REDACTED_SECRET]");
            copiedUntil = valueEnd;
            searchFrom = valueEnd;
        }
        sanitized.append(input, copiedUntil, input.length());
        return sanitized.toString();
    }

    private static int findClosingQuote(
            String input,
            int fromIndex,
            char quote
    ) {
        boolean escaped = false;
        for (int index = fromIndex; index < input.length(); index++) {
            char current = input.charAt(index);
            if (escaped) {
                escaped = false;
            } else if (current == '\\') {
                escaped = true;
            } else if (current == quote) {
                return index;
            }
        }
        return -1;
    }

    private static int findUnquotedValueEnd(String input, int fromIndex) {
        int index = fromIndex;
        while (index < input.length()) {
            char current = input.charAt(index);
            if (Character.isWhitespace(current)
                    || current == ',' || current == ';'
                    || current == '}' || current == '&'
                    || current == '\"' || current == '\'') {
                break;
            }
            index++;
        }
        return index;
    }

    private static boolean containsUnredactedNamedSecret(String input) {
        Matcher matcher = NAMED_SECRET_PREFIX.matcher(input);
        while (matcher.find()) {
            int valueStart = matcher.end();
            if (valueStart >= input.length()) {
                continue;
            }
            char first = input.charAt(valueStart);
            if (first == '\"' || first == '\'') {
                int closingQuote = findClosingQuote(
                        input,
                        valueStart + 1,
                        first
                );
                if (closingQuote < 0
                        || !"[REDACTED_SECRET]".equals(input.substring(
                        valueStart + 1,
                        closingQuote
                ))) {
                    return true;
                }
                continue;
            }
            int valueEnd = findUnquotedValueEnd(input, valueStart);
            if (!"[REDACTED_SECRET]".equals(input.substring(
                    valueStart,
                    valueEnd
            ))) {
                return true;
            }
        }
        return false;
    }

    /** 重建结构化摘要，保证所有可持久化文本字段都经过脱敏。 */
    public ChatSummaryContent sanitize(ChatSummaryContent content) {
        Objects.requireNonNull(content, "待脱敏摘要不能为空");
        return new ChatSummaryContent(
                content.schemaVersion(),
                sanitize(content.topic()),
                sanitize(content.currentState()),
                content.conversationFacts().stream()
                        .map(this::sanitizeFact)
                        .toList(),
                content.decisions().stream()
                        .map(this::sanitizeItem)
                        .toList(),
                content.openQuestions().stream()
                        .map(this::sanitizeItem)
                        .toList(),
                content.importantEntities().stream()
                        .map(this::sanitizeEntity)
                        .toList()
        );
    }

    private ChatSummaryFact sanitizeFact(ChatSummaryFact fact) {
        return new ChatSummaryFact(
                sanitize(fact.content()),
                fact.sourceType(),
                fact.sourceSequence()
        );
    }

    private ChatSummaryItem sanitizeItem(ChatSummaryItem item) {
        return new ChatSummaryItem(
                sanitize(item.content()),
                item.sourceSequence()
        );
    }

    private ChatSummaryEntity sanitizeEntity(ChatSummaryEntity entity) {
        return new ChatSummaryEntity(
                sanitize(entity.entityType()),
                sanitize(entity.displayValue()),
                entity.sourceType(),
                entity.sourceSequence()
        );
    }
}
