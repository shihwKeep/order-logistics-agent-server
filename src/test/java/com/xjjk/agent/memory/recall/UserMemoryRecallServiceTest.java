package com.xjjk.agent.memory.recall;

import com.xjjk.agent.identity.domain.AgentIdentity;
import com.xjjk.agent.memory.config.MemoryRetrievalProperties;
import com.xjjk.agent.memory.config.UserMemoryProperties;
import com.xjjk.agent.memory.domain.MemoryCategory;
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
}
