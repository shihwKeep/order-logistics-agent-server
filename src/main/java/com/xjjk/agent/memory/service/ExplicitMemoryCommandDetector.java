package com.xjjk.agent.memory.service;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ExplicitMemoryCommandDetector {

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
        if (input == null) {
            return Optional.empty();
        }
        String normalized = normalizeWhitespace(input);
        Optional<CommandText> permanent = detectPermanent(normalized);
        return permanent.isPresent() ? permanent : detect(normalized, NORMAL_PREFIXES, false);
    }

    static boolean requestsPermanentRetention(String input) {
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
        for (String prefix : prefixes) {
            if (!input.startsWith(prefix)) {
                continue;
            }
            return command(input.substring(prefix.length()), permanent);
        }
        return Optional.empty();
    }

    private Optional<CommandText> command(String rawPayload, boolean permanent) {
        String payload = stripOuterPunctuation(rawPayload);
        if (payload.isBlank()
                || payload.codePointCount(0, payload.length()) > maxPayloadCodePoints
                || MIXED_INTENT_MARKERS.stream().anyMatch(payload::contains)) {
            return Optional.empty();
        }
        return Optional.of(new CommandText(payload, permanent));
    }

    static String normalizeWhitespace(String value) {
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
    }
}
