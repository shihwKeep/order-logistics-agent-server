package com.xjjk.agent.prompt;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 使用受控字面量占位符渲染提示词，不执行 SpEL 或其他表达式。 */
@Component
public class StrictPromptTemplateRenderer {

    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\{([A-Za-z][A-Za-z0-9]*)}");

    public String render(
            String configurationPath,
            String template,
            Map<String, String> parameters) {
        if (!StringUtils.hasText(configurationPath)) {
            throw new IllegalArgumentException("提示词配置路径不能为空");
        }
        if (!StringUtils.hasText(template)) {
            throw invalid(configurationPath, "模板不能为空");
        }
        Objects.requireNonNull(parameters, "模板参数不能为空");

        Set<String> placeholders = placeholders(template);
        Set<String> provided = new LinkedHashSet<>(parameters.keySet());
        Set<String> missing = new LinkedHashSet<>(placeholders);
        missing.removeAll(provided);
        Set<String> unexpected = new LinkedHashSet<>(provided);
        unexpected.removeAll(placeholders);
        if (!missing.isEmpty() || !unexpected.isEmpty()) {
            throw invalid(configurationPath,
                    "占位符不匹配，缺少=" + missing + "，多余=" + unexpected);
        }

        String rendered = template;
        for (String placeholder : placeholders) {
            String value = Objects.requireNonNull(parameters.get(placeholder),
                    "模板参数值不能为空: " + placeholder);
            rendered = rendered.replace("{" + placeholder + "}", value);
        }
        if (PLACEHOLDER.matcher(rendered).find()) {
            throw invalid(configurationPath, "渲染后仍存在未解析占位符");
        }
        return rendered;
    }

    public Set<String> placeholders(String template) {
        if (template == null) {
            return Set.of();
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        Set<String> names = new LinkedHashSet<>();
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return Set.copyOf(names);
    }

    private static IllegalStateException invalid(String path, String reason) {
        return new IllegalStateException("提示词配置无效: " + path + "，" + reason);
    }
}
