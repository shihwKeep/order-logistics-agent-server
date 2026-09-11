package com.xjjk.agent.memory.service;

import com.xjjk.agent.memory.domain.MemoryCategory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 第一版显式记忆的封闭语法解析器。
 *
 * <p>用户原文和模型改写必须分别完整落入同一类别语法，并得到相同的规范值。
 * 持久化内容由这里确定性生成，不直接信任模型自由文本。</p>
 */
public class MemoryCategoryContentPolicy {

    private static final List<SemanticGroup> ANSWER_STYLES = List.of(
            group("简洁", "简短", "简洁", "精简", "言简意赅", "短一些", "不要太长"),
            group("详细", "详细", "详尽", "展开说明"),
            group("先给结论", "先结论", "结论优先", "先给结论"),
            group("分点", "分点", "分条", "列表"),
            group("表格", "表格"),
            group("专业", "专业"),
            group("口语化", "口语", "口语化", "通俗"),
            group("直接", "直接")
    );
    private static final List<SemanticGroup> LANGUAGES = List.of(
            group("中文", "中文", "汉语"),
            group("英文", "英文", "英语"),
            group("日文", "日文", "日语"),
            group("韩文", "韩文", "韩语"),
            group("法文", "法文", "法语"),
            group("德文", "德文", "德语"),
            group("西班牙文", "西班牙文", "西班牙语")
    );
    private static final List<SemanticGroup> WORK_SCOPES = List.of(
            group("Java", "java"), group("Python", "python"), group("前端", "前端"),
            group("后端", "后端"), group("开发", "开发"), group("测试", "测试"),
            group("运维", "运维"), group("客服", "客服"), group("售后", "售后"),
            group("销售", "销售"), group("财务", "财务"),
            group("人力资源", "人力资源", "hr"), group("设计", "设计"),
            group("产品", "产品"), group("项目管理", "项目管理")
    );
    private static final List<String> ANSWER_FILLERS = List.of(
            "用户", "给我", "以后", "今后", "后续", "默认", "回答风格", "回答",
            "回复", "答复", "偏好", "希望", "请", "尽量", "采用", "使用", "要",
            "更", "一点", "一些", "内容", "我"
    );
    private static final List<String> LANGUAGE_FILLERS = List.of(
            "用户", "给我", "以后", "今后", "后续", "默认", "使用的语言是", "语言",
            "回答", "回复", "交流", "沟通", "偏好使用", "偏好", "希望", "请",
            "尽量", "采用", "使用", "用", "以", "我"
    );
    private static final List<String> WORK_FILLERS = List.of(
            "用户", "本人", "平时", "主要", "经常", "常用", "工作范围", "工作",
            "范围", "从事", "负责", "使用", "技术", "方向", "岗位", "领域",
            "常", "做", "是", "我"
    );
    private static final Pattern PREFERRED_NAME = Pattern.compile(
            "^(?:以后|今后|后续)?(?:请)?(?:叫我|称呼我|称我为|把我称为|"
                    + "用户希望被称为|用户偏好的称呼是|昵称是)"
                    + "(老师|先生|女士|同学|伙伴|朋友)[\\s，,。.!！？?：:；;]*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );
    private static final Pattern PUNCTUATION = Pattern.compile("[\\s，,。.!！？?：:、；;（）()]+");

    public boolean isAllowed(MemoryCategory category, String evidence, String content) {
        Optional<String> evidenceValue = canonicalize(category, evidence);
        Optional<String> contentValue = canonicalize(category, content);
        return evidenceValue.isPresent() && evidenceValue.equals(contentValue);
    }

    public Optional<String> canonicalize(MemoryCategory category, String value) {
        Objects.requireNonNull(category, "category");
        String normalized = normalize(value);
        if (normalized.isBlank()) {
            return Optional.empty();
        }
        return switch (category) {
            case PROFILE_PREFERRED_NAME -> preferredName(normalized);
            case PREFERENCE_LANGUAGE -> singleGroup(
                    normalized, LANGUAGES, LANGUAGE_FILLERS, "用户偏好使用", "交流");
            case PREFERENCE_ANSWER_STYLE -> singleGroup(
                    normalized, ANSWER_STYLES, ANSWER_FILLERS, "用户偏好", "回答");
            case WORK_COMMON_SCOPE -> workScope(normalized);
        };
    }

    private static Optional<String> preferredName(String value) {
        Matcher matcher = PREFERRED_NAME.matcher(value);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return Optional.of("用户希望被称为" + matcher.group(1));
    }

    private static Optional<String> singleGroup(String value, List<SemanticGroup> groups,
                                                List<String> fillers, String prefix, String suffix) {
        List<SemanticGroup> matched = matchingGroups(value, groups);
        if (matched.size() != 1 || !containsOnlyKnownPhrases(value, matched, fillers)) {
            return Optional.empty();
        }
        return Optional.of(prefix + matched.getFirst().canonical() + suffix);
    }

    private static Optional<String> workScope(String value) {
        List<SemanticGroup> matched = matchingGroups(value, WORK_SCOPES);
        if (matched.isEmpty() || !containsOnlyKnownPhrases(value, matched, WORK_FILLERS)) {
            return Optional.empty();
        }
        StringBuilder scope = new StringBuilder();
        matched.stream()
                .sorted(Comparator.comparingInt(group -> firstIndex(value, group.terms())))
                .map(SemanticGroup::canonical)
                .distinct()
                .forEach(scope::append);
        return Optional.of("用户常用工作范围是" + scope);
    }

    private static List<SemanticGroup> matchingGroups(String value, List<SemanticGroup> groups) {
        List<SemanticGroup> matched = new ArrayList<>();
        for (SemanticGroup group : groups) {
            if (group.terms().stream().anyMatch(value::contains)) {
                matched.add(group);
            }
        }
        return matched;
    }

    private static boolean containsOnlyKnownPhrases(String value, List<SemanticGroup> matched,
                                                    List<String> fillers) {
        String remainder = value;
        List<String> removable = new ArrayList<>();
        matched.forEach(group -> bestMatchedTerm(value, group.terms()).ifPresent(removable::add));
        removable.addAll(fillers);
        removable.sort(Comparator.comparingInt(String::length).reversed());
        for (String phrase : removable) {
            remainder = remainder.replace(phrase, "");
        }
        return PUNCTUATION.matcher(remainder).replaceAll("").isEmpty();
    }

    private static Optional<String> bestMatchedTerm(String value, List<String> terms) {
        return terms.stream()
                .filter(value::contains)
                .min(Comparator.comparingInt((String term) -> value.indexOf(term))
                        .thenComparing(Comparator.comparingInt(String::length).reversed()));
    }

    private static int firstIndex(String value, List<String> terms) {
        return terms.stream().mapToInt(value::indexOf).filter(index -> index >= 0).min()
                .orElse(Integer.MAX_VALUE);
    }

    private static SemanticGroup group(String canonical, String... terms) {
        return new SemanticGroup(canonical, List.of(terms));
    }

    private static String normalize(String value) {
        return value == null ? "" : ExplicitMemoryCommandDetector.normalizeWhitespace(value)
                .toLowerCase(Locale.ROOT);
    }

    private record SemanticGroup(String canonical, List<String> terms) {
    }
}
