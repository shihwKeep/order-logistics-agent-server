package com.xjjk.agent.memory.recall;

import com.xjjk.agent.identity.domain.AgentIdentity;
import com.xjjk.agent.memory.config.MemoryRetrievalProperties;
import com.xjjk.agent.memory.config.UserMemoryProperties;
import com.xjjk.agent.memory.domain.MemoryCategory;
import com.xjjk.agent.memory.domain.MemoryTemporalScope;
import com.xjjk.agent.memory.persistence.entity.UserMemoryEntity;
import com.xjjk.agent.memory.persistence.entity.UserMemorySettingEntity;
import com.xjjk.agent.memory.persistence.mapper.MemorySuppressionMapper;
import com.xjjk.agent.memory.persistence.mapper.UserMemoryMapper;
import com.xjjk.agent.memory.persistence.mapper.UserMemorySettingMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UserMemoryRecallServiceTest {
    private static final LocalDateTime NOW = LocalDateTime.parse("2026-09-12T08:00:00");
    private static final AgentIdentity IDENTITY =
            new AgentIdentity(9L, "account", "name", 2L, 7L);

    @Test
    void loadsGlobalExplicitAndValidatesSemanticCandidatesAgainstMysql() {
        UserMemorySettingMapper settings = mock(UserMemorySettingMapper.class);
        UserMemoryMapper memories = mock(UserMemoryMapper.class);
        MemorySuppressionMapper suppressions = mock(MemorySuppressionMapper.class);
        MemoryRecallGateway gateway = mock(MemoryRecallGateway.class);
        when(settings.selectOwned(7L, 9L)).thenReturn(setting(true, 3L));
        UserMemoryEntity global = memory("global", 1L, "USER_EXPLICIT",
                "PREFERENCE_ANSWER_STYLE", "preference.answer_style", "用户偏好简洁回答");
        when(memories.selectGlobalExplicit(7L, 9L, 3L, NOW, 3))
                .thenReturn(List.of(global));
        when(gateway.retrieve(7L, 9L, 3L, "我平时主要使用什么编程语言？"))
                .thenReturn(new MemoryRecallGatewayResult(true, List.of(
                        new MemoryRecallCandidateSignal(
                                "valid", 2L, 0.92, 1, Set.of("VECTOR")),
                        new MemoryRecallCandidateSignal(
                                "wrong-version", 9L, 0.90, 2, Set.of("KEYWORD"))),
                        "v1", "NONE", "OK"));
        UserMemoryEntity valid = memory("valid", 2L, "AUTO_EXTRACT",
                "WORK_COMMON_SCOPE", "work.common_scope.java", "用户主要从事 Java 开发");
        UserMemoryEntity wrongVersion = memory("wrong-version", 8L, "AUTO_EXTRACT",
                "WORK_COMMON_SCOPE", "work.common_scope.other", "用户从事后端开发");
        when(memories.selectActiveCandidates(7L, 9L, 3L,
                List.of("valid", "wrong-version"), NOW))
                .thenReturn(List.of(valid, wrongVersion));
        when(suppressions.selectActiveKeys(7L, 9L, 3L,
                List.of("preference.answer_style", "work.common_scope.java"), 20))
                .thenReturn(List.of());

        UserMemoryRecallResult result = service(
                settings, memories, suppressions, gateway, true)
                .recall(IDENTITY, "我平时主要使用什么编程语言？");

        assertThat(result.memories()).extracting(RecalledMemory::memoryId)
                .containsExactly("global", "valid");
        assertThat(result.semanticAttempted()).isTrue();
    }

    @Test
    void disabledSettingAndReadFailureBothFailClosedWithoutBlockingChat() {
        UserMemorySettingMapper settings = mock(UserMemorySettingMapper.class);
        UserMemoryMapper memories = mock(UserMemoryMapper.class);
        MemorySuppressionMapper suppressions = mock(MemorySuppressionMapper.class);
        MemoryRecallGateway gateway = mock(MemoryRecallGateway.class);
        when(settings.selectOwned(7L, 9L)).thenReturn(setting(false, 3L));
        UserMemoryRecallService service = service(
                settings, memories, suppressions, gateway, true);

        assertThat(service.recall(IDENTITY, "我喜欢什么语言？").memories()).isEmpty();
        verify(gateway, never()).retrieve(7L, 9L, 3L, "我喜欢什么语言？");

        when(settings.selectOwned(7L, 9L)).thenThrow(new IllegalStateException("db down"));
        assertThat(service.recall(IDENTITY, "我喜欢什么语言？").memories()).isEmpty();
    }

    @Test
    void distinguishesEmptyUninitializedDisabledInvalidAndUnavailableResults() {
        UserMemorySettingMapper settings = mock(UserMemorySettingMapper.class);
        UserMemoryMapper memories = mock(UserMemoryMapper.class);
        MemorySuppressionMapper suppressions = mock(MemorySuppressionMapper.class);
        MemoryRecallGateway gateway = mock(MemoryRecallGateway.class);
        UserMemoryRecallService service = service(
                settings, memories, suppressions, gateway, true);

        assertThat(service.recall(0L, 9L, "我的编程语言是什么？").status())
                .isEqualTo(UserMemoryRecallStatus.INVALID_REQUEST);

        when(settings.selectOwned(7L, 9L)).thenReturn(setting(true, 3L));
        when(memories.selectGlobalExplicit(7L, 9L, 3L, NOW, 3))
                .thenReturn(List.of());
        when(gateway.retrieve(7L, 9L, 3L, "我的编程语言是什么？"))
                .thenReturn(new MemoryRecallGatewayResult(
                        true, List.of(), "v1", "NONE", "NO_CANDIDATE"));
        assertThat(service.recall(IDENTITY, "我的编程语言是什么？").status())
                .isEqualTo(UserMemoryRecallStatus.AVAILABLE);

        when(settings.selectOwned(7L, 9L)).thenReturn(null);
        assertThat(service.recall(IDENTITY, "我的编程语言是什么？").status())
                .isEqualTo(UserMemoryRecallStatus.NOT_INITIALIZED);

        when(settings.selectOwned(7L, 9L)).thenReturn(setting(false, 3L));
        assertThat(service.recall(IDENTITY, "我的编程语言是什么？").status())
                .isEqualTo(UserMemoryRecallStatus.DISABLED);

        when(settings.selectOwned(7L, 9L)).thenThrow(new IllegalStateException("db down"));
        assertThat(service.recall(IDENTITY, "我的编程语言是什么？").status())
                .isEqualTo(UserMemoryRecallStatus.UNAVAILABLE);
    }

    @Test
    void recallsExactCategoryFromMysqlWithoutSemanticGateway() {
        UserMemorySettingMapper settings = mock(UserMemorySettingMapper.class);
        UserMemoryMapper memories = mock(UserMemoryMapper.class);
        MemorySuppressionMapper suppressions = mock(MemorySuppressionMapper.class);
        MemoryRecallGateway gateway = mock(MemoryRecallGateway.class);
        when(settings.selectOwned(7L, 9L)).thenReturn(setting(true, 3L));
        UserMemoryEntity java = memory("java", 1L, "AUTO_EXTRACT",
                "WORK_COMMON_SCOPE", "work.common_scope.java",
                "用户常用工作范围是Java开发");
        UserMemoryEntity malformed = memory("malformed", 1L, "AUTO_EXTRACT",
                "WORK_COMMON_SCOPE", "work.common_scope.python",
                "用户常用工作范围是Python开发");
        malformed.setVisibility("VISIBLE");
        when(memories.selectActiveByCategory(
                7L, 9L, 3L, "WORK_COMMON_SCOPE", NOW, 20))
                .thenReturn(List.of(java, malformed));
        when(suppressions.selectActiveKeys(
                7L, 9L, 3L, List.of("work.common_scope.java"), 20))
                .thenReturn(List.of());

        UserMemoryRecallResult result = service(
                settings, memories, suppressions, gateway, true)
                .recallByCategory(IDENTITY, MemoryCategory.WORK_COMMON_SCOPE);

        assertThat(result.status()).isEqualTo(UserMemoryRecallStatus.AVAILABLE);
        assertThat(result.memories()).extracting(RecalledMemory::memoryId)
                .containsExactly("java");
        assertThat(result.semanticAttempted()).isFalse();
        assertThat(result.semanticResultCode()).isEqualTo("MYSQL_CATEGORY");
        verifyNoInteractions(gateway);
    }

    @Test
    void recallsStructuredPredicateFromMysqlWithExplicitPriority() {
        UserMemorySettingMapper settings = mock(UserMemorySettingMapper.class);
        UserMemoryMapper memories = mock(UserMemoryMapper.class);
        MemorySuppressionMapper suppressions = mock(MemorySuppressionMapper.class);
        MemoryRecallGateway gateway = mock(MemoryRecallGateway.class);
        when(settings.selectOwned(7L, 9L)).thenReturn(setting(true, 3L));
        UserMemoryEntity elixir = memory("elixir", 2L, "USER_EXPLICIT",
                "WORK_COMMON_SCOPE", "work.primary_programming_language",
                "用户主要使用 Elixir 进行开发");
        elixir.setSchemaVersion(2);
        elixir.setMemoryType("WORK_CONTEXT");
        elixir.setPredicateName("primary_programming_language");
        elixir.setValueJson("\"Elixir\"");
        elixir.setStability("STABLE");
        elixir.setVerificationMethod("EXPLICIT_SEMANTIC");
        when(memories.selectActiveByPredicates(
                7L, 9L, 3L, List.of("primary_programming_language"),
                "WORK_COMMON_SCOPE", "CURRENT", NOW, 20)).thenReturn(List.of(elixir));
        when(suppressions.selectActiveKeys(
                7L, 9L, 3L, List.of("work.primary_programming_language"), 20))
                .thenReturn(List.of());

        UserMemoryRecallResult result = service(
                settings, memories, suppressions, gateway, true)
                .recallByPredicates(IDENTITY,
                        List.of("primary_programming_language"),
                        MemoryCategory.WORK_COMMON_SCOPE);

        assertThat(result.memories()).singleElement().satisfies(memory -> {
            assertThat(memory.predicateName()).isEqualTo("primary_programming_language");
            assertThat(memory.valueJson()).isEqualTo("\"Elixir\"");
        });
        assertThat(result.semanticResultCode()).isEqualTo("MYSQL_PREDICATE");
        verifyNoInteractions(gateway);
    }

    @Test
    void isolatesCurrentAndHistoricalStructuredFactsEvenIfMapperOverReturns() {
        UserMemorySettingMapper settings = mock(UserMemorySettingMapper.class);
        UserMemoryMapper memories = mock(UserMemoryMapper.class);
        MemorySuppressionMapper suppressions = mock(MemorySuppressionMapper.class);
        MemoryRecallGateway gateway = mock(MemoryRecallGateway.class);
        when(settings.selectOwned(7L, 9L)).thenReturn(setting(true, 3L));
        UserMemoryEntity current = temporalMemory(
                "current-seat", "work.occupation", "坐席", "CURRENT");
        UserMemoryEntity historical = temporalMemory(
                "history-java", "work.occupation.history.1", "Java开发", "HISTORICAL");
        when(memories.selectActiveByPredicates(
                7L, 9L, 3L, List.of("occupation"),
                "WORK_COMMON_SCOPE", "CURRENT", NOW, 20))
                .thenReturn(List.of(current, historical));
        when(memories.selectActiveByPredicates(
                7L, 9L, 3L, List.of("occupation"),
                "WORK_COMMON_SCOPE", "HISTORICAL", NOW, 20))
                .thenReturn(List.of(historical, current));
        when(suppressions.selectActiveKeys(
                7L, 9L, 3L, List.of("work.occupation"), 20)).thenReturn(List.of());
        when(suppressions.selectActiveKeys(
                7L, 9L, 3L, List.of("work.occupation.history.1"), 20))
                .thenReturn(List.of());
        UserMemoryRecallService service = service(
                settings, memories, suppressions, gateway, true);

        UserMemoryRecallResult currentResult = service.recallByPredicates(
                IDENTITY, List.of("occupation"), MemoryCategory.WORK_COMMON_SCOPE,
                MemoryTemporalScope.CURRENT);
        UserMemoryRecallResult historicalResult = service.recallByPredicates(
                IDENTITY, List.of("occupation"), MemoryCategory.WORK_COMMON_SCOPE,
                MemoryTemporalScope.HISTORICAL);

        assertThat(currentResult.memories()).extracting(RecalledMemory::memoryId)
                .containsExactly("current-seat");
        assertThat(historicalResult.memories()).extracting(RecalledMemory::memoryId)
                .containsExactly("history-java");
    }

    @Test
    void attemptsSemanticRecallForSafeQueryOutsideOldKeywordList() {
        UserMemorySettingMapper settings = mock(UserMemorySettingMapper.class);
        UserMemoryMapper memories = mock(UserMemoryMapper.class);
        MemorySuppressionMapper suppressions = mock(MemorySuppressionMapper.class);
        MemoryRecallGateway gateway = mock(MemoryRecallGateway.class);
        when(settings.selectOwned(7L, 9L)).thenReturn(setting(true, 3L));
        when(memories.selectGlobalExplicit(7L, 9L, 3L, NOW, 3)).thenReturn(List.of());
        when(gateway.retrieve(7L, 9L, 3L, "这个方案适合我吗"))
                .thenReturn(new MemoryRecallGatewayResult(
                        true, List.of(), "v2", "NONE", "NO_CANDIDATE"));

        UserMemoryRecallResult result = service(
                settings, memories, suppressions, gateway, true)
                .recall(IDENTITY, "这个方案适合我吗");

        assertThat(result.semanticAttempted()).isTrue();
        verify(gateway).retrieve(7L, 9L, 3L, "这个方案适合我吗");
    }

    @Test
    void semanticRecallRejectsHistoricalFactsForCurrentQuestion() {
        UserMemorySettingMapper settings = mock(UserMemorySettingMapper.class);
        UserMemoryMapper memories = mock(UserMemoryMapper.class);
        MemorySuppressionMapper suppressions = mock(MemorySuppressionMapper.class);
        MemoryRecallGateway gateway = mock(MemoryRecallGateway.class);
        when(settings.selectOwned(7L, 9L)).thenReturn(setting(true, 3L));
        when(memories.selectGlobalExplicit(7L, 9L, 3L, NOW, 3)).thenReturn(List.of());
        when(gateway.retrieve(7L, 9L, 3L, "结合我现在的职业给建议"))
                .thenReturn(new MemoryRecallGatewayResult(true, List.of(
                        new MemoryRecallCandidateSignal(
                                "history-java", 1L, 0.98, 1, Set.of("VECTOR")),
                        new MemoryRecallCandidateSignal(
                                "current-seat", 1L, 0.90, 2, Set.of("KEYWORD"))),
                        "v2", "NONE", "OK"));
        UserMemoryEntity historical = temporalMemory(
                "history-java", "work.occupation.history.1", "Java开发", "HISTORICAL");
        UserMemoryEntity current = temporalMemory(
                "current-seat", "work.occupation", "坐席", "CURRENT");
        when(memories.selectActiveCandidates(7L, 9L, 3L,
                List.of("history-java", "current-seat"), NOW))
                .thenReturn(List.of(historical, current));
        when(suppressions.selectActiveKeys(
                7L, 9L, 3L, List.of("work.occupation"), 20)).thenReturn(List.of());

        UserMemoryRecallResult result = service(
                settings, memories, suppressions, gateway, true)
                .recall(IDENTITY, "结合我现在的职业给建议");

        assertThat(result.memories()).extracting(RecalledMemory::memoryId)
                .containsExactly("current-seat");
    }

    @Test
    void categoryRecallDistinguishesUninitializedDisabledAndDatabaseFailure() {
        UserMemorySettingMapper settings = mock(UserMemorySettingMapper.class);
        UserMemoryMapper memories = mock(UserMemoryMapper.class);
        MemorySuppressionMapper suppressions = mock(MemorySuppressionMapper.class);
        MemoryRecallGateway gateway = mock(MemoryRecallGateway.class);
        UserMemoryRecallService service = service(
                settings, memories, suppressions, gateway, true);

        when(settings.selectOwned(7L, 9L)).thenReturn(null);
        assertThat(service.recallByCategory(
                IDENTITY, MemoryCategory.WORK_COMMON_SCOPE).status())
                .isEqualTo(UserMemoryRecallStatus.NOT_INITIALIZED);

        when(settings.selectOwned(7L, 9L)).thenReturn(setting(false, 3L));
        assertThat(service.recallByCategory(
                IDENTITY, MemoryCategory.WORK_COMMON_SCOPE).status())
                .isEqualTo(UserMemoryRecallStatus.DISABLED);

        when(settings.selectOwned(7L, 9L))
                .thenThrow(new IllegalStateException("db down"));
        assertThat(service.recallByCategory(
                IDENTITY, MemoryCategory.WORK_COMMON_SCOPE).status())
                .isEqualTo(UserMemoryRecallStatus.UNAVAILABLE);
    }

    @Test
    void skipsSemanticGatewayForUnrelatedQueryButStillLoadsGlobalPreferences() {
        UserMemorySettingMapper settings = mock(UserMemorySettingMapper.class);
        UserMemoryMapper memories = mock(UserMemoryMapper.class);
        MemorySuppressionMapper suppressions = mock(MemorySuppressionMapper.class);
        MemoryRecallGateway gateway = mock(MemoryRecallGateway.class);
        when(settings.selectOwned(7L, 9L)).thenReturn(setting(true, 3L));
        UserMemoryEntity global = memory("global", 1L, "USER_EXPLICIT",
                "PREFERENCE_LANGUAGE", "preference.language", "用户偏好中文回答");
        when(memories.selectGlobalExplicit(7L, 9L, 3L, NOW, 3))
                .thenReturn(List.of(global));
        when(suppressions.selectActiveKeys(7L, 9L, 3L,
                List.of("preference.language"), 20)).thenReturn(List.of());

        UserMemoryRecallResult result = service(
                settings, memories, suppressions, gateway, true)
                .recall(IDENTITY, "查询订单 C24101816040");

        assertThat(result.memories()).extracting(RecalledMemory::memoryId)
                .containsExactly("global");
        assertThat(result.semanticAttempted()).isFalse();
        verify(gateway, never()).retrieve(7L, 9L, 3L, "查询订单 C24101816040");
    }

    private UserMemoryRecallService service(
            UserMemorySettingMapper settings,
            UserMemoryMapper memories,
            MemorySuppressionMapper suppressions,
            MemoryRecallGateway gateway,
            boolean enabled) {
        return new UserMemoryRecallService(
                settings, memories, suppressions, gateway,
                new MemoryRecallGate(), new MemoryCandidateSelector(),
                properties(enabled), new MemoryRetrievalProperties(20, 5, 3),
                Clock.fixed(Instant.parse("2026-09-12T08:00:00Z"), ZoneOffset.UTC));
    }

    private UserMemoryProperties properties(boolean enabled) {
        return new UserMemoryProperties(
                enabled, true, 256, 512, 512, 50, 365,
                "memory-explicit-v1", "qwen-plus", 0.1,
                Duration.ofSeconds(10), 2, 32);
    }

    private UserMemorySettingEntity setting(boolean enabled, long generation) {
        UserMemorySettingEntity setting = new UserMemorySettingEntity();
        setting.setMemoryEnabled(enabled);
        setting.setMemoryGeneration(generation);
        return setting;
    }

    private UserMemoryEntity memory(
            String id, long version, String source, String category,
            String key, String content) {
        UserMemoryEntity value = new UserMemoryEntity();
        value.setMemoryId(id);
        value.setTenantId(7L);
        value.setUserId(9L);
        value.setMemoryGeneration(3L);
        value.setVersion(version);
        value.setSourceType(source);
        value.setVisibility("USER_EXPLICIT".equals(source) ? "VISIBLE" : "HIDDEN");
        value.setCategory(category);
        value.setCanonicalKey(key);
        value.setContent(content);
        value.setConfidence(new BigDecimal("0.95"));
        value.setStatus("ACTIVE");
        value.setExpiresAt(LocalDateTime.parse("2027-09-12T08:00:00"));
        value.setUpdatedAt(LocalDateTime.parse("2026-09-12T07:00:00"));
        return value;
    }

    private UserMemoryEntity temporalMemory(
            String id, String key, String value, String temporalScope) {
        UserMemoryEntity memory = memory(
                id, 1L, "AUTO_EXTRACT", "WORK_COMMON_SCOPE", key,
                "HISTORICAL".equals(temporalScope)
                        ? "用户过去的职业是" + value : "用户当前的职业是" + value);
        memory.setSchemaVersion(3);
        memory.setMemoryType("WORK_CONTEXT");
        memory.setPredicateName("occupation");
        memory.setValueJson("\"" + value + "\"");
        memory.setStability("TIME_BOUND");
        memory.setVerificationMethod("SEMANTIC_MODEL");
        memory.setObservedAt(LocalDateTime.parse("2026-09-12T07:00:00"));
        memory.setValidFrom("CURRENT".equals(temporalScope)
                ? LocalDateTime.parse("2026-09-12T07:00:00") : null);
        memory.setTemporalScope(temporalScope);
        return memory;
    }
}
