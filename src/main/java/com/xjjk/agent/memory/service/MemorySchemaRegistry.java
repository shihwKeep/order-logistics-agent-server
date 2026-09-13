package com.xjjk.agent.memory.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xjjk.agent.memory.domain.MemoryFactCandidate;
import com.xjjk.agent.memory.domain.MemoryTemporalScope;
import com.xjjk.agent.memory.domain.MemoryType;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.xjjk.agent.memory.service.MemoryCandidateValidationException.Reason.SCHEMA;
import static com.xjjk.agent.memory.service.MemoryCandidateValidationException.Reason.UNSUPPORTED;

/**
 * 将开放自然语言候选映射为受控的事实模式。
 *
 * <p>注册表只处理 predicate 和事实值，不查看或匹配用户整句话。</p>
 */
@Component
public class MemorySchemaRegistry {

    private static final int MAX_VALUE_CODE_POINTS = 128;
    private static final int HISTORICAL_HASH_HEX_LENGTH = 32;
    private static final Pattern SAFE_NAME = Pattern.compile(
            "[\\p{L}\\p{N} ·_\\-]{1,32}", Pattern.UNICODE_CASE);
    private static final Pattern SAFE_PROGRAMMING_LANGUAGE = Pattern.compile(
            "[\\p{L}\\p{N}+#._ /\\-]{1,32}", Pattern.UNICODE_CASE);
    private static final Pattern SAFE_OPEN_VALUE = Pattern.compile(
            "[\\p{L}\\p{N}+#._ /，,、：:；;（）()\\-]{1,128}",
            Pattern.UNICODE_CASE);
    private static final Pattern SAFE_OPEN_PREDICATE = Pattern.compile(
            "[a-z][a-z0-9_]{0,63}");
    private static final Pattern AGE = Pattern.compile("([0-9]{1,3})(?:岁)?");
    private static final Set<String> SENSITIVE_PROFILE_PREDICATE_TOKENS = Set.of(
            "phone", "mobile", "tel", "telephone", "contact", "id", "identity", "card",
            "bank", "account", "address", "location", "health", "medical", "disease",
            "diagnosis");

    private static final Map<String, String> ANSWER_LANGUAGES = Map.ofEntries(
            Map.entry("中文", "中文"), Map.entry("汉语", "中文"),
            Map.entry("英文", "英文"), Map.entry("英语", "英文"),
            Map.entry("日文", "日文"), Map.entry("日语", "日文"),
            Map.entry("韩文", "韩文"), Map.entry("韩语", "韩文"),
            Map.entry("法文", "法文"), Map.entry("法语", "法文"),
            Map.entry("德文", "德文"), Map.entry("德语", "德文"),
            Map.entry("西班牙文", "西班牙文"), Map.entry("西班牙语", "西班牙文"));
    private static final Map<String, String> ANSWER_STYLES = Map.ofEntries(
            Map.entry("简短", "简洁"), Map.entry("简洁", "简洁"),
            Map.entry("精简", "简洁"), Map.entry("言简意赅", "简洁"),
            Map.entry("详细", "详细"), Map.entry("详尽", "详细"),
            Map.entry("先给结论", "先给结论"), Map.entry("结论优先", "先给结论"),
            Map.entry("分点", "分点"), Map.entry("分条", "分点"),
            Map.entry("列表", "分点"), Map.entry("表格", "表格"),
            Map.entry("专业", "专业"), Map.entry("口语", "口语化"),
            Map.entry("口语化", "口语化"), Map.entry("通俗", "口语化"),
            Map.entry("直接", "直接"));
    private static final Set<String> DETERMINISTIC_ANSWER_LANGUAGES =
            Set.copyOf(ANSWER_LANGUAGES.values());
    private static final Set<String> DETERMINISTIC_ANSWER_STYLES =
            Set.copyOf(ANSWER_STYLES.values());
    private static final Map<String, String> PROGRAMMING_ALIASES = Map.ofEntries(
            Map.entry("java", "Java"), Map.entry("python", "Python"),
            Map.entry("go", "Go"), Map.entry("golang", "Go"),
            Map.entry("kotlin", "Kotlin"), Map.entry("javascript", "JavaScript"),
            Map.entry("js", "JavaScript"), Map.entry("typescript", "TypeScript"),
            Map.entry("ts", "TypeScript"), Map.entry("c#", "C#"),
            Map.entry("c sharp", "C#"), Map.entry("c++", "C++"),
            Map.entry("c plus plus", "C++"), Map.entry("rust", "Rust"),
            Map.entry("arkts", "ArkTS"), Map.entry("php", "PHP"),
            Map.entry("ruby", "Ruby"), Map.entry("scala", "Scala"),
            Map.entry("dart", "Dart"), Map.entry("lua", "Lua"),
            Map.entry("swift", "Swift"), Map.entry("objective-c", "Objective-C"));
    private static final Set<String> DETERMINISTIC_PROGRAMMING_LANGUAGES =
            Set.copyOf(PROGRAMMING_ALIASES.values());
    private static final Map<String, MemoryType> AUTHORITATIVE_PREDICATE_TYPES =
            Map.ofEntries(
                    Map.entry("preferred_name", MemoryType.PROFILE),
                    Map.entry("age", MemoryType.PROFILE),
                    Map.entry("answer_language", MemoryType.COMMUNICATION_PREFERENCE),
                    Map.entry("answer_style", MemoryType.RESPONSE_PREFERENCE),
                    Map.entry("occupation", MemoryType.WORK_CONTEXT),
                    Map.entry("current_employer", MemoryType.WORK_CONTEXT),
                    Map.entry("primary_programming_language", MemoryType.WORK_CONTEXT),
                    Map.entry("technology_stack", MemoryType.WORK_CONTEXT),
                    Map.entry("common_scope", MemoryType.WORK_CONTEXT));

    private final ObjectMapper objectMapper;

    public MemorySchemaRegistry(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public SchemaResolution resolve(MemoryFactCandidate candidate) {
        MemoryFactCandidate normalized = normalizeCandidate(candidate);
        return switch (normalized.memoryType()) {
            case PROFILE -> profile(normalized);
            case COMMUNICATION_PREFERENCE -> aliasedStable(
                    normalized, "answer_language", "communication.answer_language",
                    "PREFERENCE_LANGUAGE", this::answerLanguage,
                    value -> isHistorical(normalized)
                            ? "用户过去偏好使用" + value + "交流"
                            : "用户偏好使用" + value + "交流",
                    DETERMINISTIC_ANSWER_LANGUAGES);
            case RESPONSE_PREFERENCE -> aliasedStable(
                    normalized, "answer_style", "response.answer_style",
                    "PREFERENCE_ANSWER_STYLE", this::answerStyle,
                    value -> isHistorical(normalized)
                            ? "用户过去偏好" + value + "回答"
                            : "用户偏好" + value + "回答",
                    DETERMINISTIC_ANSWER_STYLES);
            case WORK_CONTEXT -> work(normalized);
            case STABLE_PREFERENCE -> open(
                    normalized, "STABLE_PREFERENCE",
                    "用户的稳定偏好是", "用户过去的稳定偏好是");
            case STABLE_USER_FACT -> open(
                    normalized, "STABLE_USER_FACT",
                    "用户的稳定信息是", "用户过去的稳定信息是");
        };
    }

    /**
     * 已知 predicate 的归属类型由服务端模式决定，模型只负责抽取语义和值。
     */
    public MemoryFactCandidate normalizeCandidate(MemoryFactCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate");
        MemoryType authoritativeType = AUTHORITATIVE_PREDICATE_TYPES.get(
                candidate.predicate());
        if (authoritativeType == null || authoritativeType == candidate.memoryType()) {
            return candidate;
        }
        return new MemoryFactCandidate(
                authoritativeType,
                candidate.predicate(),
                candidate.value(),
                candidate.valueEvidence(),
                candidate.evidenceText(),
                candidate.stability(),
                candidate.temporalScope(),
                candidate.confidence());
    }

    private SchemaResolution profile(MemoryFactCandidate candidate) {
        return switch (candidate.predicate()) {
            case "preferred_name" -> stable(
                    candidate, "preferred_name", "profile.preferred_name",
                    "PROFILE_PREFERRED_NAME", this::safeName,
                    value -> isHistorical(candidate)
                            ? "用户过去希望被称为" + value
                            : "用户希望被称为" + value,
                    false);
            case "age" -> stable(
                    candidate, "age", "profile.age", "PROFILE_PERSONAL_FACT",
                    this::age, value -> "用户曾表示年龄为" + value + "岁", true);
            default -> openProfile(candidate);
        };
    }

    /** 开放画像事实保留受控 predicate/value，并始终要求语义复核。 */
    private SchemaResolution openProfile(MemoryFactCandidate candidate) {
        requireSafeOpenProfilePredicate(candidate.predicate());
        String canonical = safeOpenValue(candidate.valueEvidence());
        requireStrictlyEquivalent(candidate.value(), canonical, this::safeOpenValue);
        String key = "profile.open." + MemoryHashing.sha256(candidate.predicate());
        String prefix = isHistorical(candidate)
                ? "用户曾提供的个人画像事实（" : "用户提供的个人画像事实（";
        String content = prefix + candidate.predicate() + "）是" + canonical;
        return resolved(candidate, key, content, canonical,
                "PROFILE_PERSONAL_FACT", true);
    }

    private SchemaResolution work(MemoryFactCandidate candidate) {
        return switch (candidate.predicate()) {
            case "occupation" -> stable(
                    candidate, "occupation", "work.occupation", "WORK_COMMON_SCOPE",
                    this::safeOpenValue,
                    value -> isHistorical(candidate)
                            ? "用户过去的职业是" + value
                            : "用户当前的职业是" + value,
                    true);
            case "current_employer" -> stable(
                    candidate, "current_employer", "work.current_employer",
                    "WORK_COMMON_SCOPE", this::safeOpenValue,
                    value -> isHistorical(candidate)
                            ? "用户过去的工作单位是" + value
                            : "用户当前工作单位是" + value,
                    true);
            case "primary_programming_language" -> {
                String canonical = programmingLanguage(candidate.valueEvidence());
                requireEquivalent(candidate.value(), canonical, this::programmingLanguage);
                yield resolved(
                        candidate,
                        "work.primary_programming_language",
                        isHistorical(candidate)
                                ? "用户过去主要使用 " + canonical + " 进行开发"
                                : "用户主要使用 " + canonical + " 进行开发",
                        canonical,
                        "WORK_COMMON_SCOPE",
                        !DETERMINISTIC_PROGRAMMING_LANGUAGES.contains(canonical));
            }
            case "technology_stack" -> stable(
                    candidate, "technology_stack", "work.technology_stack",
                    "WORK_COMMON_SCOPE", this::safeOpenValue,
                    value -> isHistorical(candidate)
                            ? "用户过去常用技术栈是" + value
                            : "用户常用技术栈是" + value,
                    true);
            case "common_scope" -> stable(
                    candidate, "common_scope", "work.common_scope", "WORK_COMMON_SCOPE",
                    this::safeOpenValue,
                    value -> isHistorical(candidate)
                            ? "用户过去常用工作范围是" + value
                            : "用户常用工作范围是" + value,
                    true);
            default -> openWork(candidate);
        };
    }

    /**
     * 未预注册的稳定工作事实使用受控开放模式：predicate 必须是短 snake_case，
     * canonical key 由服务端哈希生成，事实值仍需证据一致并经过语义复核。
     */
    private SchemaResolution openWork(MemoryFactCandidate candidate) {
        requireSafeOpenPredicate(candidate.predicate());
        String canonical = safeOpenValue(candidate.valueEvidence());
        requireEquivalent(candidate.value(), canonical, this::safeOpenValue);
        String key = "work.open." + MemoryHashing.sha256(candidate.predicate());
        String content = isHistorical(candidate)
                ? "用户过去的工作背景是" + canonical
                : "用户的稳定工作背景是" + canonical;
        return resolved(candidate, key, content, canonical, "WORK_COMMON_SCOPE", true);
    }

    private SchemaResolution stable(
            MemoryFactCandidate candidate,
            String expectedPredicate,
            String canonicalKey,
            String legacyCategory,
            Function<String, String> normalizer,
            Function<String, String> renderer,
            boolean semanticVerification) {
        if (!expectedPredicate.equals(candidate.predicate())) {
            throw rejected(SCHEMA);
        }
        String canonical = normalizer.apply(candidate.valueEvidence());
        requireEquivalent(candidate.value(), canonical, normalizer);
        return resolved(candidate, canonicalKey, renderer.apply(canonical), canonical,
                legacyCategory, semanticVerification);
    }

    private SchemaResolution aliasedStable(
            MemoryFactCandidate candidate,
            String expectedPredicate,
            String canonicalKey,
            String legacyCategory,
            Function<String, String> normalizer,
            Function<String, String> renderer,
            Set<String> deterministicValues) {
        if (!expectedPredicate.equals(candidate.predicate())) {
            throw rejected(SCHEMA);
        }
        String canonical = normalizer.apply(candidate.valueEvidence());
        requireEquivalent(candidate.value(), canonical, normalizer);
        return resolved(candidate, canonicalKey, renderer.apply(canonical), canonical,
                legacyCategory, !deterministicValues.contains(canonical));
    }

    private SchemaResolution open(
            MemoryFactCandidate candidate,
            String legacyCategory,
            String currentPrefix,
            String historicalPrefix) {
        String canonical = safeOpenValue(candidate.valueEvidence());
        requireStrictlyEquivalent(candidate.value(), canonical, this::safeOpenValue);
        String prefix = isHistorical(candidate) ? historicalPrefix : currentPrefix;
        String content = prefix + canonical;
        String key = "fact." + candidate.memoryType().name().toLowerCase(Locale.ROOT)
                + "." + MemoryHashing.sha256(currentPrefix + canonical);
        return resolved(candidate, key, content, canonical, legacyCategory, true);
    }

    private SchemaResolution resolved(
            MemoryFactCandidate candidate,
            String key,
            String content,
            String canonicalValue,
            String legacyCategory,
            boolean semanticVerification) {
        try {
            String temporalKey = isHistorical(candidate)
                    ? key + ".history." + MemoryHashing.sha256(
                            canonicalValue + "\u0000" + normalize(candidate.evidenceText()))
                            .substring(0, HISTORICAL_HASH_HEX_LENGTH)
                    : key;
            return new SchemaResolution(
                    temporalKey, content, objectMapper.writeValueAsString(canonicalValue),
                    legacyCategory, semanticVerification || isHistorical(candidate));
        } catch (JsonProcessingException exception) {
            throw rejected(UNSUPPORTED);
        }
    }

    private void requireEquivalent(
            String proposed,
            String canonicalEvidence,
            Function<String, String> normalizer) {
        final String canonicalProposed;
        try {
            canonicalProposed = normalizer.apply(proposed);
        } catch (MemoryCandidateValidationException exception) {
            throw rejected(UNSUPPORTED);
        }
        if (!canonicalEvidence.equalsIgnoreCase(canonicalProposed)) {
            throw rejected(UNSUPPORTED);
        }
    }

    private void requireStrictlyEquivalent(
            String proposed,
            String canonicalEvidence,
            Function<String, String> normalizer) {
        final String canonicalProposed;
        try {
            canonicalProposed = normalizer.apply(proposed);
        } catch (MemoryCandidateValidationException exception) {
            throw rejected(UNSUPPORTED);
        }
        if (!canonicalEvidence.equals(canonicalProposed)) {
            throw rejected(UNSUPPORTED);
        }
    }

    private String safeName(String raw) {
        String value = normalize(raw);
        if (!SAFE_NAME.matcher(value).matches()) {
            throw rejected(UNSUPPORTED);
        }
        return value;
    }

    private String age(String raw) {
        String value = normalize(raw);
        Matcher matcher = AGE.matcher(value);
        if (!matcher.matches()) {
            throw rejected(UNSUPPORTED);
        }
        int parsed = Integer.parseInt(matcher.group(1));
        if (parsed > 120) {
            throw rejected(UNSUPPORTED);
        }
        return Integer.toString(parsed);
    }

    private String answerLanguage(String raw) {
        String value = normalize(raw);
        String canonical = ANSWER_LANGUAGES.get(value);
        return canonical == null ? safeOpenValue(value) : canonical;
    }

    private String answerStyle(String raw) {
        String value = normalize(raw);
        String canonical = ANSWER_STYLES.get(value);
        return canonical == null ? safeOpenValue(value) : canonical;
    }

    private String programmingLanguage(String raw) {
        String value = normalize(raw);
        if (!SAFE_PROGRAMMING_LANGUAGE.matcher(value).matches()) {
            throw rejected(UNSUPPORTED);
        }
        return PROGRAMMING_ALIASES.getOrDefault(
                value.toLowerCase(Locale.ROOT), value);
    }

    private String safeOpenValue(String raw) {
        String value = normalize(raw);
        int codePoints = value.codePointCount(0, value.length());
        if (codePoints < 1 || codePoints > MAX_VALUE_CODE_POINTS
                || !SAFE_OPEN_VALUE.matcher(value).matches()) {
            throw rejected(UNSUPPORTED);
        }
        return value;
    }

    private static void requireSafeOpenPredicate(String predicate) {
        if (!SAFE_OPEN_PREDICATE.matcher(predicate).matches()) {
            throw rejected(SCHEMA);
        }
    }

    private static void requireSafeOpenProfilePredicate(String predicate) {
        requireSafeOpenPredicate(predicate);
        for (String token : predicate.split("_")) {
            if (SENSITIVE_PROFILE_PREDICATE_TOKENS.contains(token)) {
                throw rejected(SCHEMA);
            }
        }
    }

    private static boolean isHistorical(MemoryFactCandidate candidate) {
        return candidate.temporalScope() == MemoryTemporalScope.HISTORICAL;
    }

    private static String normalize(String raw) {
        if (raw == null) {
            throw rejected(UNSUPPORTED);
        }
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC)
                .strip().replaceAll("\\s+", " ");
        if (normalized.isBlank()) {
            throw rejected(UNSUPPORTED);
        }
        return normalized;
    }

    private static MemoryCandidateValidationException rejected(
            MemoryCandidateValidationException.Reason reason) {
        return new MemoryCandidateValidationException(reason);
    }

    public record SchemaResolution(
            String canonicalKey,
            String canonicalContent,
            String valueJson,
            String legacyCategory,
            boolean requiresSemanticVerification) {
    }
}
