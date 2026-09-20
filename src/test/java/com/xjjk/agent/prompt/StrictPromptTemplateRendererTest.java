package com.xjjk.agent.prompt;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StrictPromptTemplateRendererTest {

    private final StrictPromptTemplateRenderer renderer =
            new StrictPromptTemplateRenderer();

    @Test
    void replacesLiteralPlaceholdersAndPreservesMultilineValues() {
        String rendered = renderer.render(
                "summary.user-template",
                "版本={version};输入={input}",
                Map.of("version", "v1", "input", "第一行\n第二行"));

        assertThat(rendered).isEqualTo("版本=v1;输入=第一行\n第二行");
    }

    @Test
    void rejectsMissingTemplateParameterWithConfigurationPath() {
        assertThatThrownBy(() -> renderer.render(
                "summary.user-template", "输入={input}", Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("summary.user-template")
                .hasMessageContaining("input");
    }

    @Test
    void rejectsUnexpectedTemplateParameter() {
        assertThatThrownBy(() -> renderer.render(
                "summary.user-template", "输入={input}",
                Map.of("input", "内容", "extra", "多余")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("summary.user-template")
                .hasMessageContaining("extra");
    }

    @Test
    void doesNotEvaluateExpressionLikeInput() {
        String rendered = renderer.render(
                "memory.implicit.user-template", "输入={input}",
                Map.of("input", "#{T(java.lang.Runtime)}"));

        assertThat(rendered).isEqualTo("输入=#{T(java.lang.Runtime)}");
    }
}
