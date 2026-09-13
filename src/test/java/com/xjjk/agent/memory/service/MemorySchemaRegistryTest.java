package com.xjjk.agent.memory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xjjk.agent.memory.domain.MemoryFactCandidate;
import com.xjjk.agent.memory.domain.MemoryStability;
import com.xjjk.agent.memory.domain.MemoryTemporalScope;
import com.xjjk.agent.memory.domain.MemoryType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemorySchemaRegistryTest {

    private final MemorySchemaRegistry registry =
            new MemorySchemaRegistry(new ObjectMapper());

    @Test
    void resolvesStableSlotsWithoutInspectingTheSurroundingSentence() {
        var first = registry.resolve(candidate(
                MemoryType.WORK_CONTEXT, "primary_programming_language",
                "Java", "Java"));
        var second = registry.resolve(candidate(
                MemoryType.WORK_CONTEXT, "primary_programming_language",
                "Java", "Java"));

        assertThat(first.canonicalKey())
                .isEqualTo("work.primary_programming_language");
        assertThat(first.canonicalContent())
                .isEqualTo("用户主要使用 Java 进行开发");
        assertThat(first.valueJson()).isEqualTo("\"Java\"");
        assertThat(first.requiresSemanticVerification()).isFalse();
        assertThat(second).isEqualTo(first);
    }

    @Test
    void supportsValueLevelAliasesAndOpenProgrammingLanguages() {
        assertThat(registry.resolve(candidate(
                MemoryType.WORK_CONTEXT, "primary_programming_language",
                "JavaScript", "js")).canonicalContent())
                .isEqualTo("用户主要使用 JavaScript 进行开发");
        assertThat(registry.resolve(candidate(
                MemoryType.COMMUNICATION_PREFERENCE, "answer_language",
                "英文", "英语")).canonicalContent())
                .isEqualTo("用户偏好使用英文交流");

        var openLanguage = registry.resolve(candidate(
                MemoryType.WORK_CONTEXT, "primary_programming_language",
                "Elixir", "Elixir"));
        assertThat(openLanguage.canonicalContent())
                .isEqualTo("用户主要使用 Elixir 进行开发");
        assertThat(openLanguage.requiresSemanticVerification()).isTrue();
    }

    @Test
    void supportsOpenCommunicationLanguagesAndAnswerStylesWithoutEnumeratingThem() {
        var language = registry.resolve(candidate(
                MemoryType.COMMUNICATION_PREFERENCE, "answer_language",
                "葡萄牙语", "葡萄牙语"));
        var style = registry.resolve(candidate(
                MemoryType.RESPONSE_PREFERENCE, "answer_style",
                "苏格拉底式引导", "苏格拉底式引导"));

        assertThat(language.canonicalContent()).isEqualTo("用户偏好使用葡萄牙语交流");
        assertThat(language.requiresSemanticVerification()).isTrue();
        assertThat(style.canonicalContent()).isEqualTo("用户偏好苏格拉底式引导回答");
        assertThat(style.requiresSemanticVerification()).isTrue();
    }

    @Test
    void createsServerOwnedKeysForOpenFacts() {
        var result = registry.resolve(candidate(
                MemoryType.STABLE_PREFERENCE, "arbitrary_model_key",
                "我喜欢先看示例再看解释", "我喜欢先看示例再看解释"));

        assertThat(result.canonicalKey())
                .matches("fact\\.stable_preference\\.[0-9a-f]{64}");
        assertThat(result.canonicalKey()).doesNotContain("arbitrary_model_key");
        assertThat(result.requiresSemanticVerification()).isTrue();
    }

    @Test
    void resolvesCurrentEmployerAsACanonicalWorkContextSlot() {
        var result = registry.resolve(candidate(
                MemoryType.WORK_CONTEXT, "current_employer", "享佳", "享佳"));

        assertThat(result.canonicalKey()).isEqualTo("work.current_employer");
        assertThat(result.canonicalContent()).isEqualTo("用户当前工作单位是享佳");
        assertThat(result.valueJson()).isEqualTo("\"享佳\"");
        assertThat(result.legacyCategory()).isEqualTo("WORK_COMMON_SCOPE");
        assertThat(result.requiresSemanticVerification()).isTrue();
    }

    @Test
    void acceptsBoundedOpenWorkFactsWithoutTrustingTheModelPredicateAsAKey() {
        var result = registry.resolve(candidate(
                MemoryType.WORK_CONTEXT, "preferred_framework", "Phoenix", "Phoenix"));

        assertThat(result.canonicalKey()).matches("work\\.open\\.[0-9a-f]{64}");
        assertThat(result.canonicalKey()).doesNotContain("preferred_framework");
        assertThat(result.canonicalContent()).isEqualTo("用户的稳定工作背景是Phoenix");
        assertThat(result.requiresSemanticVerification()).isTrue();
    }

    @Test
    void rejectsUnsafeOpenWorkPredicateNames() {
        assertThatThrownBy(() -> registry.resolve(candidate(
                MemoryType.WORK_CONTEXT, "Bad-Predicate", "Phoenix", "Phoenix")))
                .isInstanceOf(MemoryCandidateValidationException.class)
                .extracting(error -> ((MemoryCandidateValidationException) error).reason())
                .isEqualTo(MemoryCandidateValidationException.Reason.SCHEMA);
    }

    @Test
    void rejectsUnknownPredicatesAndUnsupportedValueRewrites() {
        assertThatThrownBy(() -> registry.resolve(candidate(
                MemoryType.WORK_CONTEXT, "primary_programming_language",
                "Python", "Java")))
                .isInstanceOf(MemoryCandidateValidationException.class)
                .extracting(error -> ((MemoryCandidateValidationException) error).reason())
                .isEqualTo(MemoryCandidateValidationException.Reason.UNSUPPORTED);
    }

    @Test
    void resolvesAgeFromPlainOrSuffixedEvidence() {
        var result = registry.resolve(candidate(
                MemoryType.PROFILE, "age", "32", "32岁",
                "我今年32岁", MemoryStability.TIME_BOUND,
                MemoryTemporalScope.CURRENT));
        var historical = registry.resolve(candidate(
                MemoryType.PROFILE, "age", "32岁", "32",
                "我当时32岁", MemoryStability.TIME_BOUND,
                MemoryTemporalScope.HISTORICAL));

        assertThat(result.canonicalKey()).isEqualTo("profile.age");
        assertThat(result.canonicalContent()).isEqualTo("用户曾表示年龄为32岁");
        assertThat(result.valueJson()).isEqualTo("\"32\"");
        assertThat(result.legacyCategory()).isEqualTo("PROFILE_PERSONAL_FACT");
        assertThat(result.requiresSemanticVerification()).isTrue();
        assertThat(historical.canonicalKey())
                .matches("profile\\.age\\.history\\.[0-9a-f]{32}");
        assertThat(historical.canonicalContent()).isEqualTo("用户曾表示年龄为32岁");
        assertThat(historical.valueJson()).isEqualTo("\"32\"");
        assertThat(historical.requiresSemanticVerification()).isTrue();
    }

    @Test
    void acceptsAgeRangeBoundaries() {
        for (String age : new String[]{"0", "120"}) {
            assertThat(registry.resolve(candidate(
                    MemoryType.PROFILE, "age", age, age + "岁")).valueJson())
                    .isEqualTo("\"" + age + "\"");
        }
    }

    @Test
    void rejectsInvalidAgesAndEvidenceRewrites() {
        for (String invalid : new String[]{"-1", "32.5", "121", "大约32岁"}) {
            assertThatThrownBy(() -> registry.resolve(candidate(
                    MemoryType.PROFILE, "age", invalid, invalid)))
                    .isInstanceOf(MemoryCandidateValidationException.class)
                    .extracting(error -> ((MemoryCandidateValidationException) error).reason())
                    .isEqualTo(MemoryCandidateValidationException.Reason.UNSUPPORTED);
        }
        assertThatThrownBy(() -> registry.resolve(candidate(
                MemoryType.PROFILE, "age", "32", "33岁")))
                .isInstanceOf(MemoryCandidateValidationException.class)
                .extracting(error -> ((MemoryCandidateValidationException) error).reason())
                .isEqualTo(MemoryCandidateValidationException.Reason.UNSUPPORTED);
    }

    @Test
    void acceptsBoundedOpenProfileFactsWithoutUsingThePredicateAsTheKey() {
        var current = registry.resolve(candidate(
                MemoryType.PROFILE, "favorite_city", "杭州", "杭州"));
        var historical = registry.resolve(candidate(
                MemoryType.PROFILE, "favorite_city", "杭州", "杭州",
                "我以前最喜欢杭州", MemoryTemporalScope.HISTORICAL));

        assertThat(current.canonicalKey()).matches("profile\\.open\\.[0-9a-f]{64}");
        assertThat(current.canonicalKey()).doesNotContain("favorite_city");
        assertThat(current.canonicalContent())
                .isEqualTo("用户提供的个人画像事实（favorite_city）是杭州");
        assertThat(current.legacyCategory()).isEqualTo("PROFILE_PERSONAL_FACT");
        assertThat(current.requiresSemanticVerification()).isTrue();
        assertThat(historical.canonicalKey())
                .matches(current.canonicalKey() + "\\.history\\.[0-9a-f]{32}");
        assertThat(historical.canonicalContent())
                .isEqualTo("用户曾提供的个人画像事实（favorite_city）是杭州");
    }

    @Test
    void rejectsUnsafeOpenProfilePredicateNames() {
        assertThatThrownBy(() -> registry.resolve(candidate(
                MemoryType.PROFILE, "Bad-Predicate", "杭州", "杭州")))
                .isInstanceOf(MemoryCandidateValidationException.class)
                .extracting(error -> ((MemoryCandidateValidationException) error).reason())
                .isEqualTo(MemoryCandidateValidationException.Reason.SCHEMA);
    }

    @Test
    void rejectsSensitiveOpenProfilePredicates() {
        for (String predicate : new String[]{
                "phone", "mobile_number", "tel", "contact_phone", "id_card",
                "identity_number", "national_id", "id_number", "credit_card",
                "bank_account", "account_id",
                "home_address", "current_location", "health_status", "medical_history",
                "disease_history", "diagnosis_result"}) {
            assertThatThrownBy(() -> registry.resolve(candidate(
                    MemoryType.PROFILE, predicate, "普通值", "普通值")))
                    .as(predicate)
                    .isInstanceOf(MemoryCandidateValidationException.class)
                    .extracting(error -> ((MemoryCandidateValidationException) error).reason())
                    .isEqualTo(MemoryCandidateValidationException.Reason.SCHEMA);
        }
    }

    @Test
    void comparesOpenValuesAfterUnicodeNormalizationButNotCaseFolding() {
        assertThat(registry.resolve(candidate(
                MemoryType.PROFILE, "display_label", "Ａlice", "Alice"))
                .valueJson()).isEqualTo("\"Alice\"");
        assertThat(registry.resolve(candidate(
                MemoryType.STABLE_USER_FACT, "display_label", "Ａlice", "Alice"))
                .valueJson()).isEqualTo("\"Alice\"");

        assertUnsupported(candidate(
                MemoryType.PROFILE, "display_label", "Alice", "alice"));
        assertUnsupported(candidate(
                MemoryType.STABLE_USER_FACT, "display_label", "Alice", "alice"));

        assertThat(registry.resolve(candidate(
                MemoryType.WORK_CONTEXT, "primary_programming_language", "JAVA", "java"))
                .valueJson()).isEqualTo("\"Java\"");
    }

    @Test
    void historicalAgeUsesAnIdempotentNonConflictingKeyAndPreservesScopeOnTypeCorrection() {
        MemoryFactCandidate candidate = candidate(
                MemoryType.WORK_CONTEXT, "age", "32", "32岁",
                "我以前说过自己32岁", MemoryTemporalScope.HISTORICAL);

        var first = registry.resolve(candidate);
        var second = registry.resolve(candidate);

        assertThat(first.canonicalKey())
                .matches("profile\\.age\\.history\\.[0-9a-f]{32}");
        assertThat(first.canonicalKey()).isNotEqualTo("profile.age");
        assertThat(second).isEqualTo(first);
        assertThat(first.canonicalContent()).isEqualTo("用户曾表示年龄为32岁");
    }

    @Test
    void rendersOccupationAndEmployerAccordingToTemporalScope() {
        var currentOccupation = registry.resolve(candidate(
                MemoryType.WORK_CONTEXT, "occupation", "架构师", "架构师"));
        var historicalOccupation = registry.resolve(candidate(
                MemoryType.WORK_CONTEXT, "occupation", "教师", "教师",
                "我过去是教师", MemoryTemporalScope.HISTORICAL));
        var historicalEmployer = registry.resolve(candidate(
                MemoryType.WORK_CONTEXT, "current_employer", "某大学", "某大学",
                "我过去在某大学工作", MemoryTemporalScope.HISTORICAL));

        assertThat(currentOccupation.canonicalKey()).isEqualTo("work.occupation");
        assertThat(currentOccupation.canonicalContent()).isEqualTo("用户当前的职业是架构师");
        assertThat(historicalOccupation.canonicalKey())
                .matches("work\\.occupation\\.history\\.[0-9a-f]{32}");
        assertThat(historicalOccupation.canonicalContent()).isEqualTo("用户过去的职业是教师");
        assertThat(historicalEmployer.canonicalKey())
                .matches("work\\.current_employer\\.history\\.[0-9a-f]{32}");
        assertThat(historicalEmployer.canonicalContent())
                .isEqualTo("用户过去的工作单位是某大学");
    }

    @Test
    void boundsHistoricalKeysToThePersistenceColumnLength() {
        var current = registry.resolve(candidate(
                MemoryType.STABLE_PREFERENCE, "learning_order",
                "先看例子再看原理", "先看例子再看原理"));
        var result = registry.resolve(candidate(
                MemoryType.STABLE_PREFERENCE, "learning_order",
                "先看例子再看原理", "先看例子再看原理",
                "我过去喜欢先看例子再看原理", MemoryTemporalScope.HISTORICAL));

        assertThat(result.canonicalKey()).hasSizeLessThanOrEqualTo(128);
        assertThat(result.canonicalKey())
                .matches(current.canonicalKey() + "\\.history\\.[0-9a-f]{32}");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("historicalFacts")
    void rendersEveryHistoricalMemoryTypeWithoutClaimingCurrentState(
            String description,
            MemoryFactCandidate candidate,
            String expectedContent) {
        var result = registry.resolve(candidate);

        assertThat(result.canonicalContent()).isEqualTo(expectedContent);
        assertThat(result.canonicalKey()).contains(".history.");
        assertThat(result.requiresSemanticVerification()).isTrue();
    }

    private static Stream<Arguments> historicalFacts() {
        return Stream.of(
                historicalFact("profile", MemoryType.PROFILE, "preferred_name",
                        "Alice", "用户过去希望被称为Alice"),
                historicalFact("communication", MemoryType.COMMUNICATION_PREFERENCE,
                        "answer_language", "英语", "用户过去偏好使用英文交流"),
                historicalFact("response", MemoryType.RESPONSE_PREFERENCE,
                        "answer_style", "简短", "用户过去偏好简洁回答"),
                historicalFact("work", MemoryType.WORK_CONTEXT,
                        "primary_programming_language", "java",
                        "用户过去主要使用 Java 进行开发"),
                historicalFact("stable preference", MemoryType.STABLE_PREFERENCE,
                        "learning_order", "先看示例", "用户过去的稳定偏好是先看示例"),
                historicalFact("stable user fact", MemoryType.STABLE_USER_FACT,
                        "personal_note", "住过杭州", "用户过去的稳定信息是住过杭州")
        );
    }

    private static Arguments historicalFact(
            String description,
            MemoryType type,
            String predicate,
            String value,
            String expectedContent) {
        return Arguments.of(description,
                candidate(type, predicate, value, value,
                        "以前的事实是" + value, MemoryStability.STABLE,
                        MemoryTemporalScope.HISTORICAL),
                expectedContent);
    }

    private static MemoryFactCandidate candidate(
            MemoryType type,
            String predicate,
            String value,
            String valueEvidence) {
        return candidate(type, predicate, value, valueEvidence,
                "上下文中的" + valueEvidence + "事实", MemoryStability.STABLE,
                MemoryTemporalScope.CURRENT);
    }

    private static MemoryFactCandidate candidate(
            MemoryType type,
            String predicate,
            String value,
            String valueEvidence,
            String evidenceText,
            MemoryTemporalScope temporalScope) {
        return candidate(type, predicate, value, valueEvidence, evidenceText,
                MemoryStability.STABLE, temporalScope);
    }

    private static MemoryFactCandidate candidate(
            MemoryType type,
            String predicate,
            String value,
            String valueEvidence,
            String evidenceText,
            MemoryStability stability,
            MemoryTemporalScope temporalScope) {
        return new MemoryFactCandidate(
                type, predicate, value, valueEvidence,
                evidenceText, stability, temporalScope, 0.96);
    }

    private void assertUnsupported(MemoryFactCandidate candidate) {
        assertThatThrownBy(() -> registry.resolve(candidate))
                .isInstanceOf(MemoryCandidateValidationException.class)
                .extracting(error -> ((MemoryCandidateValidationException) error).reason())
                .isEqualTo(MemoryCandidateValidationException.Reason.UNSUPPORTED);
    }
}
