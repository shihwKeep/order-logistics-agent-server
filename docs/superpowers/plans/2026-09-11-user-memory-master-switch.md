# User Memory Master Switch Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a backward-compatible per-user memory master switch that blocks explicit writes when disabled while preserving memory listing, deletion, and full clearing.

**Architecture:** Flyway V11 adds `memory_enabled` to the owner-scoped settings row. The settings API reads and atomically updates both the new master switch and the existing auto-extract switch; a focused policy service performs the friendly pre-check, while `ExplicitMemoryWriteService` rechecks the locked settings row inside the write transaction to close races.

**Tech Stack:** Java 21, Spring Boot 3.5, MyBatis-Plus, Flyway, MySQL 8, Jakarta Validation, JUnit 5, Mockito, AssertJ, Maven

---

## Repository map

- Repository: `D:\GitCode\order-logistics-agent-server`
- Worktree: `C:\Users\shwfo\.config\superpowers\worktrees\order-logistics-agent-server\user-memory-management`
- Branch: `feature/user-memory-management`
- Cross-repository design: `D:\GitCode\order-logistics-agent-web\docs\superpowers\specs\2026-09-11-user-memory-management-panel-design.md`

## File structure

- Create `src/main/resources/db/migration/V11__add_user_memory_master_switch.sql`: add the default-on owner switch.
- Create `src/main/java/com/xjjk/agent/memory/service/UserMemoryPolicyService.java`: expose one reusable effective master-switch decision.
- Create `src/main/java/com/xjjk/agent/memory/service/UserMemoryDisabledException.java`: carry the expected closed-switch control flow without converting it into a storage failure.
- Create `src/test/java/com/xjjk/agent/memory/service/UserMemoryPolicyServiceTest.java`: cover missing/default, enabled, disabled, and corrupt-null settings.
- Modify setting DTO, entity, mapper, query service, controller, and their tests: expose partial updates without breaking clients that only send `autoExtractEnabled`.
- Modify `ExplicitMemoryCommandService` and `ExplicitMemoryWriteService`: provide a friendly rejection and a transactionally authoritative guard.
- Modify migration and MySQL integration tests: prove V10 rows upgrade to enabled.

### Task 1: Add the default-on database column

**Files:**
- Create: `src/main/resources/db/migration/V11__add_user_memory_master_switch.sql`
- Modify: `src/main/java/com/xjjk/agent/memory/persistence/entity/UserMemorySettingEntity.java`
- Modify: `src/main/java/com/xjjk/agent/memory/persistence/mapper/UserMemorySettingMapper.java`
- Modify: `src/test/java/com/xjjk/agent/memory/persistence/UserMemoryMigrationContractTest.java`
- Modify: `src/test/java/com/xjjk/agent/memory/persistence/UserMemoryMySqlIntegrationTest.java`

- [ ] **Step 1: Write the failing migration contract**

Extend `UserMemoryMigrationContractTest` with a helper that reads V11 and this assertion:

```java
@Test
void addsDefaultOnUserMemoryMasterSwitch() throws IOException {
    String sql = readMigration("/db/migration/V11__add_user_memory_master_switch.sql");
    assertThat(sql)
            .contains("ALTER TABLE agent_user_memory_setting")
            .contains("memory_enabled TINYINT(1) NOT NULL DEFAULT 1")
            .contains("AFTER memory_generation");
}

private String readMigration(String path) throws IOException {
    try (var input = getClass().getResourceAsStream(path)) {
        assertThat(input).isNotNull();
        return new String(input.readAllBytes(), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
    }
}
```

- [ ] **Step 2: Run the contract and verify RED**

Run:

```powershell
.\mvnw.cmd "-Dtest=UserMemoryMigrationContractTest" test
```

Expected: FAIL because the V11 resource does not exist.

- [ ] **Step 3: Add V11 and map the column**

Create the migration:

```sql
ALTER TABLE agent_user_memory_setting
    ADD COLUMN memory_enabled TINYINT(1) NOT NULL DEFAULT 1
        COMMENT '用户级长期记忆总开关'
        AFTER memory_generation;
```

Add to `UserMemorySettingEntity`:

```java
@TableField("memory_enabled")
private Boolean memoryEnabled;
```

Change `insertIfAbsent` so a newly created row receives both settings:

```java
@Insert("""
    INSERT IGNORE INTO agent_user_memory_setting (
        tenant_id, user_id, memory_generation, memory_enabled,
        auto_extract_enabled, created_at, updated_at
    ) VALUES (
        #{tenantId}, #{userId}, 1, #{memoryEnabled},
        #{autoExtractEnabled}, #{now}, #{now}
    )
    """)
int insertIfAbsent(long tenantId, long userId, boolean memoryEnabled,
                   boolean autoExtractEnabled, LocalDateTime now);
```

Update every existing call site to pass `true` before `properties.autoExtractDefaultEnabled()`; this preserves current behavior before a user changes the switch.

- [ ] **Step 4: Prove an existing V10 row upgrades to enabled**

In `UserMemoryMySqlIntegrationTest.migrate`, migrate through V10, insert a settings row without `memory_enabled`, then apply V11:

```java
Flyway.configure()
        .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
        .target(MigrationVersion.fromVersion("10"))
        .load()
        .migrate();
try (Connection connection = connection(); Statement statement = connection.createStatement()) {
    statement.executeUpdate("""
        INSERT INTO agent_user_memory_setting (
            tenant_id, user_id, memory_generation, auto_extract_enabled, created_at, updated_at
        ) VALUES (1, 2, 7, 1, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
        """);
}
Flyway.configure()
        .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
        .load()
        .migrate();
```

Add this assertion:

```java
@Test
void upgradesExistingSettingWithMemoryEnabledByDefault() throws Exception {
    try (Connection connection = connection(); Statement statement = connection.createStatement();
         ResultSet result = statement.executeQuery("""
             SELECT memory_enabled FROM agent_user_memory_setting
             WHERE tenant_id = 1 AND user_id = 2
             """)) {
        assertThat(result.next()).isTrue();
        assertThat(result.getBoolean("memory_enabled")).isTrue();
    }
}
```

- [ ] **Step 5: Run migration tests and commit**

Run:

```powershell
.\mvnw.cmd "-Dtest=UserMemoryMigrationContractTest,UserMemoryMySqlIntegrationTest" test
```

Expected: PASS; Flyway reports schema version 11.

Commit:

```powershell
git add src/main/resources/db/migration/V11__add_user_memory_master_switch.sql src/main/java/com/xjjk/agent/memory/persistence/entity/UserMemorySettingEntity.java src/main/java/com/xjjk/agent/memory/persistence/mapper/UserMemorySettingMapper.java src/test/java/com/xjjk/agent/memory/persistence/UserMemoryMigrationContractTest.java src/test/java/com/xjjk/agent/memory/persistence/UserMemoryMySqlIntegrationTest.java src/main/java/com/xjjk/agent/memory/service/ExplicitMemoryWriteService.java src/main/java/com/xjjk/agent/memory/service/UserMemoryManagementService.java src/main/java/com/xjjk/agent/memory/service/UserMemoryQueryService.java src/test/java/com/xjjk/agent/memory/service/ExplicitMemoryWriteServiceTest.java src/test/java/com/xjjk/agent/memory/service/UserMemoryQueryServiceTest.java src/test/java/com/xjjk/agent/memory/service/UserMemoryManagementServiceTest.java src/test/java/com/xjjk/agent/memory/service/UserMemoryClearAllTest.java
git commit -m "feat: add user memory master switch column"
```

### Task 2: Extend the settings API without breaking old clients

**Files:**
- Modify: `src/main/java/com/xjjk/agent/memory/api/dto/UpdateUserMemorySettingRequest.java`
- Modify: `src/main/java/com/xjjk/agent/memory/api/dto/UserMemorySettingResponse.java`
- Modify: `src/main/java/com/xjjk/agent/memory/persistence/mapper/UserMemorySettingMapper.java`
- Modify: `src/main/java/com/xjjk/agent/memory/service/UserMemoryQueryService.java`
- Modify: `src/main/java/com/xjjk/agent/memory/api/UserMemoryController.java`
- Modify: `src/test/java/com/xjjk/agent/memory/service/UserMemoryQueryServiceTest.java`
- Modify: `src/test/java/com/xjjk/agent/memory/api/UserMemoryControllerTest.java`

- [ ] **Step 1: Write failing setting-service tests**

Add tests proving defaults and partial updates. The central expectations are:

```java
@Test
void returnsBothDefaultsWithoutCreatingASettingRow() {
    when(settingMapper.selectOwned(1L, 2L)).thenReturn(null);

    assertThat(service.getSetting(identity))
            .isEqualTo(new UserMemorySettingResponse(true, true));
    verify(settingMapper, never()).insertIfAbsent(anyLong(), anyLong(),
            anyBoolean(), anyBoolean(), any());
}

@Test
void updatesOnlyTheMasterSwitchAndPreservesAutoExtract() {
    UserMemorySettingEntity current = setting(7L, true, false);
    when(settingMapper.selectOwnedForUpdate(1L, 2L)).thenReturn(current);
    when(settingMapper.updateSettings(1L, 2L, false, false, NOW)).thenReturn(1);

    assertThat(service.updateSetting(identity, false, null))
            .isEqualTo(new UserMemorySettingResponse(false, false));
    verify(settingMapper).updateSettings(1L, 2L, false, false, NOW);
}

@Test
void rejectsAnEmptyPartialUpdate() {
    assertThatThrownBy(() -> service.updateSetting(identity, null, null))
            .isInstanceOfSatisfying(BusinessException.class,
                    error -> assertThat(error.errorCode()).isEqualTo(ApiErrorCode.VALIDATION_ERROR));
}
```

Use the existing fixed-clock constructor in the test so `NOW` is exactly `2026-09-11T08:00:00`.

- [ ] **Step 2: Run focused tests and verify RED**

Run:

```powershell
.\mvnw.cmd "-Dtest=UserMemoryQueryServiceTest,UserMemoryControllerTest" test
```

Expected: compilation FAIL because the response, request, and service signatures still expose only `autoExtractEnabled`.

- [ ] **Step 3: Implement the backward-compatible DTO contract**

Replace the request with nullable partial fields and a bean-validation guard:

```java
public record UpdateUserMemorySettingRequest(
        Boolean memoryEnabled,
        Boolean autoExtractEnabled
) {
    @JsonIgnore
    @AssertTrue(message = "至少提供一个记忆设置")
    public boolean isUpdatePresent() {
        return memoryEnabled != null || autoExtractEnabled != null;
    }
}
```

Return both values:

```java
public record UserMemorySettingResponse(
        boolean memoryEnabled,
        boolean autoExtractEnabled
) {}
```

Replace `updateAutoExtractEnabled` with one owner-scoped update:

```java
@Update("""
    UPDATE agent_user_memory_setting
    SET memory_enabled = #{memoryEnabled},
        auto_extract_enabled = #{autoExtractEnabled},
        updated_at = #{updatedAt}
    WHERE tenant_id = #{tenantId} AND user_id = #{userId}
    """)
int updateSettings(long tenantId, long userId, boolean memoryEnabled,
                   boolean autoExtractEnabled, LocalDateTime updatedAt);
```

- [ ] **Step 4: Implement atomic read/merge/update behavior**

In `UserMemoryQueryService`:

```java
public UserMemorySettingResponse getSetting(AgentIdentity identity) {
    UserMemorySettingEntity setting = settingMapper.selectOwned(
            identity.tenantId(), identity.userId());
    return setting == null
            ? new UserMemorySettingResponse(true, properties.autoExtractDefaultEnabled())
            : new UserMemorySettingResponse(
                    Boolean.TRUE.equals(setting.getMemoryEnabled()),
                    Boolean.TRUE.equals(setting.getAutoExtractEnabled()));
}

@Transactional
public UserMemorySettingResponse updateSetting(
        AgentIdentity identity, Boolean requestedMemoryEnabled,
        Boolean requestedAutoExtractEnabled
) {
    if (requestedMemoryEnabled == null && requestedAutoExtractEnabled == null) {
        throw new BusinessException(ApiErrorCode.VALIDATION_ERROR);
    }
    LocalDateTime now = now();
    settingMapper.insertIfAbsent(identity.tenantId(), identity.userId(), true,
            properties.autoExtractDefaultEnabled(), now);
    UserMemorySettingEntity current = settingMapper.selectOwnedForUpdate(
            identity.tenantId(), identity.userId());
    if (current == null) throw new BusinessException(ApiErrorCode.MEMORY_WRITE_FAILED);
    boolean memoryEnabled = requestedMemoryEnabled != null
            ? requestedMemoryEnabled : Boolean.TRUE.equals(current.getMemoryEnabled());
    boolean autoExtractEnabled = requestedAutoExtractEnabled != null
            ? requestedAutoExtractEnabled : Boolean.TRUE.equals(current.getAutoExtractEnabled());
    if (settingMapper.updateSettings(identity.tenantId(), identity.userId(),
            memoryEnabled, autoExtractEnabled, now) != 1) {
        throw new BusinessException(ApiErrorCode.MEMORY_WRITE_FAILED);
    }
    return new UserMemorySettingResponse(memoryEnabled, autoExtractEnabled);
}
```

Update `UserMemoryController.updateSetting` to pass both request fields. Keep GET/list/delete/clear routes unchanged.

- [ ] **Step 5: Verify old and new controller payloads**

In `UserMemoryControllerTest`, assert both calls delegate correctly:

```java
assertThat(controller.updateSetting(identity,
        new UpdateUserMemorySettingRequest(false, null)).data().memoryEnabled()).isFalse();
assertThat(controller.updateSetting(identity,
        new UpdateUserMemorySettingRequest(null, false)).data().autoExtractEnabled()).isFalse();
verify(service).updateSetting(identity, false, null);
verify(service).updateSetting(identity, null, false);
```

- [ ] **Step 6: Run focused tests and commit**

Run:

```powershell
.\mvnw.cmd "-Dtest=UserMemoryQueryServiceTest,UserMemoryControllerTest" test
```

Expected: PASS.

Commit:

```powershell
git add src/main/java/com/xjjk/agent/memory/api src/main/java/com/xjjk/agent/memory/persistence/mapper/UserMemorySettingMapper.java src/main/java/com/xjjk/agent/memory/service/UserMemoryQueryService.java src/test/java/com/xjjk/agent/memory/api/UserMemoryControllerTest.java src/test/java/com/xjjk/agent/memory/service/UserMemoryQueryServiceTest.java
git commit -m "feat: expose user memory master setting"
```

### Task 3: Add the reusable switch policy and friendly rejection

**Files:**
- Create: `src/main/java/com/xjjk/agent/memory/service/UserMemoryPolicyService.java`
- Create: `src/main/java/com/xjjk/agent/memory/service/UserMemoryDisabledException.java`
- Create: `src/test/java/com/xjjk/agent/memory/service/UserMemoryPolicyServiceTest.java`
- Modify: `src/main/java/com/xjjk/agent/common/api/ApiErrorCode.java`
- Modify: `src/main/java/com/xjjk/agent/memory/service/ExplicitMemoryCommandService.java`
- Modify: `src/test/java/com/xjjk/agent/memory/service/ExplicitMemoryCommandServiceTest.java`

- [ ] **Step 1: Write failing policy and command tests**

Create `UserMemoryPolicyServiceTest`:

```java
@Test
void treatsMissingSettingAsEnabledAndStoredFalseAsDisabled() {
    when(mapper.selectOwned(1L, 2L)).thenReturn(null);
    assertThat(service.isMemoryEnabled(1L, 2L)).isTrue();

    UserMemorySettingEntity disabled = new UserMemorySettingEntity();
    disabled.setMemoryEnabled(false);
    when(mapper.selectOwned(1L, 2L)).thenReturn(disabled);
    assertThat(service.isMemoryEnabled(1L, 2L)).isFalse();
}

@Test
void requireEnabledFailsClosedForFalseOrNull() {
    for (Boolean enabled : java.util.Arrays.asList(false, null)) {
        UserMemorySettingEntity setting = new UserMemorySettingEntity();
        setting.setMemoryEnabled(enabled);
        assertThatThrownBy(() -> service.requireEnabled(setting))
                .isInstanceOf(UserMemoryDisabledException.class);
    }
}
```

In `ExplicitMemoryCommandServiceTest`, inject a mocked policy and add:

```java
@Test
void explainsHowToEnableMemoryWithoutParsingOrWritingWhenUserSwitchIsOff() {
    when(policy.isMemoryEnabled(1L, 2L)).thenReturn(false);

    assertThat(service.handle(turn(), "请永久记住：叫我老师。"))
            .isEqualTo(new ExplicitMemoryCommandResult(
                    true, false, "记忆功能已关闭，可在“我的记忆”中开启。", null));
    verifyNoInteractions(validator, writer);
    assertThat(operationCount("explicit_save", "rejected", "MEMORY_DISABLED"))
            .isEqualTo(1.0);
}
```

Default the policy mock to `true` in `setUp` so existing tests preserve their meaning.

- [ ] **Step 2: Run focused tests and verify RED**

Run:

```powershell
.\mvnw.cmd "-Dtest=UserMemoryPolicyServiceTest,ExplicitMemoryCommandServiceTest" test
```

Expected: compilation FAIL because the policy and disabled exception do not exist.

- [ ] **Step 3: Implement the policy and stable reason code**

Add to `ApiErrorCode`:

```java
MEMORY_DISABLED(
        HttpStatus.CONFLICT,
        "MEMORY_DISABLED",
        "记忆功能已关闭，可在“我的记忆”中开启"
),
```

Create:

```java
@Service
public class UserMemoryPolicyService {
    private final UserMemorySettingMapper settingMapper;

    public UserMemoryPolicyService(UserMemorySettingMapper settingMapper) {
        this.settingMapper = Objects.requireNonNull(settingMapper, "settingMapper");
    }

    public boolean isMemoryEnabled(long tenantId, long userId) {
        UserMemorySettingEntity setting = settingMapper.selectOwned(tenantId, userId);
        return setting == null || Boolean.TRUE.equals(setting.getMemoryEnabled());
    }

    public void requireEnabled(UserMemorySettingEntity setting) {
        if (setting == null || !Boolean.TRUE.equals(setting.getMemoryEnabled())) {
            throw new UserMemoryDisabledException();
        }
    }
}
```

`UserMemoryDisabledException` is a final, parameterless runtime exception with a fixed internal message and no memory content.

- [ ] **Step 4: Apply the pre-check after command detection**

Inject `UserMemoryPolicyService` into `ExplicitMemoryCommandService`. Move the existing `try` boundary so it begins immediately after command detection; this ensures a policy database failure is still mapped to `MEMORY_WRITE_FAILED`. Inside that `try`, before parsing or sensitive-content work, add:

```java
if (!policy.isMemoryEnabled(turn.tenantId(), turn.userId())) {
    metrics.rejected("explicit_save", ApiErrorCode.MEMORY_DISABLED);
    return new ExplicitMemoryCommandResult(
            true, false, "记忆功能已关闭，可在“我的记忆”中开启。", null);
}
```

Also catch `UserMemoryDisabledException` before the generic runtime catch and return the same deterministic result. This second path handles a switch that changes after the pre-check.

- [ ] **Step 5: Run focused tests and commit**

Run:

```powershell
.\mvnw.cmd "-Dtest=UserMemoryPolicyServiceTest,ExplicitMemoryCommandServiceTest" test
```

Expected: PASS; the writer is never called when the pre-check is disabled.

Commit:

```powershell
git add src/main/java/com/xjjk/agent/common/api/ApiErrorCode.java src/main/java/com/xjjk/agent/memory/service/UserMemoryPolicyService.java src/main/java/com/xjjk/agent/memory/service/UserMemoryDisabledException.java src/main/java/com/xjjk/agent/memory/service/ExplicitMemoryCommandService.java src/test/java/com/xjjk/agent/memory/service/UserMemoryPolicyServiceTest.java src/test/java/com/xjjk/agent/memory/service/ExplicitMemoryCommandServiceTest.java
git commit -m "feat: reject explicit memory when disabled"
```

### Task 4: Enforce the switch inside the write transaction

**Files:**
- Modify: `src/main/java/com/xjjk/agent/memory/service/ExplicitMemoryWriteService.java`
- Modify: `src/test/java/com/xjjk/agent/memory/service/ExplicitMemoryWriteServiceTest.java`

- [ ] **Step 1: Write the failing transaction-guard test**

Inject a real `UserMemoryPolicyService` or a focused mock into the writer, set the already locked row to disabled, and assert no memory-side call occurs:

```java
@Test
void rejectsAfterLockWhenTheMasterSwitchWasClosedAfterThePrecheck() {
    UserMemorySettingEntity setting = new UserMemorySettingEntity();
    setting.setMemoryGeneration(7L);
    setting.setMemoryEnabled(false);
    when(settingMapper.selectOwnedForUpdate(1L, 2L)).thenReturn(setting);

    assertThatThrownBy(() -> service.save(turn(), candidate()))
            .isInstanceOf(UserMemoryDisabledException.class);
    verifyNoInteractions(memoryMapper, suppressionMapper, outboxMapper, messageMapper);
}
```

Ensure the normal `setUp` row sets `memoryEnabled=true`; otherwise existing tests would correctly fail closed.

- [ ] **Step 2: Run the writer test and verify RED**

Run:

```powershell
.\mvnw.cmd "-Dtest=ExplicitMemoryWriteServiceTest" test
```

Expected: FAIL because the writer currently continues after locking a disabled row.

- [ ] **Step 3: Recheck the locked row before reading source messages or writing**

Inject `UserMemoryPolicyService` and add immediately after `selectOwnedForUpdate` and the existing null/generation validation:

```java
policy.requireEnabled(setting);
```

The transaction must still create a missing settings row with `memory_enabled=true`, lock the owner row, then check the switch. Do not put the check after message lookup or any memory/outbox mutation.

- [ ] **Step 4: Run all memory tests and commit**

Run:

```powershell
.\mvnw.cmd "-Dtest=com.xjjk.agent.memory.**" test
```

Expected: all memory tests PASS.

Commit:

```powershell
git add src/main/java/com/xjjk/agent/memory/service/ExplicitMemoryWriteService.java src/test/java/com/xjjk/agent/memory/service/ExplicitMemoryWriteServiceTest.java
git commit -m "fix: enforce memory switch in write transaction"
```

### Task 5: Verify service compatibility and behavior

**Files:**
- Verify only; modify only files already listed if verification exposes a defect.

- [ ] **Step 1: Run the complete backend suite**

Run:

```powershell
.\mvnw.cmd test
```

Expected: `BUILD SUCCESS`, 0 failures, 0 errors; the baseline was 321 tests before this feature.

- [ ] **Step 2: Check migration and diff integrity**

Run:

```powershell
git diff --check main...HEAD
git status --short
git log --oneline main..HEAD
```

Expected: no whitespace errors, no uncommitted implementation files, and only the planned feature commits.

- [ ] **Step 3: Verify the API after restarting the Agent service**

With a valid token, execute in order:

```http
GET /api/v1/me/memory-settings
PUT /api/v1/me/memory-settings
Content-Type: application/json

{"memoryEnabled":false}
```

Expected: GET and PUT include both booleans, and the PUT leaves `autoExtractEnabled` unchanged.

- [ ] **Step 4: Verify closed and reopened writes**

While disabled, send `请永久记住：叫我老师。`. Expected assistant response: `记忆功能已关闭，可在“我的记忆”中开启。`; no new `agent_user_memory` or `agent_memory_outbox` rows appear. Re-enable with `{"memoryEnabled":true}`, send the command again, and expect one active explicit memory plus its pending outbox event.

- [ ] **Step 5: Verify management remains available while disabled**

Disable memory after at least one saved item, then call:

```http
GET /api/v1/me/memories?limit=10
DELETE /api/v1/me/memories/{owned-memory-id}
DELETE /api/v1/me/memories?scope=all
```

Expected: list, single delete, and full clear remain authorized and owner-scoped even while the master switch is off.
