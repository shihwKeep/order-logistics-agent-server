package com.xjjk.agent.prompt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class PromptHardcodingContractTest {

    private static final Pattern TOOL_DESCRIPTION = Pattern.compile(
            "@Tool(?:Param)?\\([^)]*description\\s*=", Pattern.DOTALL);

    @Test
    void productionSourceDoesNotRestoreModelPromptConstantsOrAnnotationDescriptions()
            throws IOException {
        Path sourceRoot = Path.of("src", "main", "java");
        List<Path> violations;
        try (var paths = Files.walk(sourceRoot)) {
            violations = paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> violates(read(path)))
                    .toList();
        }

        assertThat(violations).isEmpty();
    }

    private static boolean violates(String source) {
        return source.contains("SYSTEM_PROMPT")
                || source.contains("SEMANTIC_SYSTEM_PROMPT")
                || TOOL_DESCRIPTION.matcher(source).find();
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取生产源码: " + path, exception);
        }
    }
}
