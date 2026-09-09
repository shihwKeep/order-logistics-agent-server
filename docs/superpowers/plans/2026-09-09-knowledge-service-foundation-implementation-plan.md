# Knowledge Service Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a runnable, independently tested Knowledge Service foundation with SSPX login, Redis-backed browser sessions, role-code authorization, tenant isolation, knowledge-base CRUD and audit logging.

**Architecture:** Create a new Java 21 Spring Boot service in `D:\GitCode\order-logistics-knowledge-service`. It authenticates against SSPX “快快外卖”, stores opaque browser sessions in HttpOnly cookies, authorizes by Application ID 444 role codes, and uses MySQL as the source of truth for tenant-scoped knowledge-base metadata.

**Tech Stack:** Java 21, Spring Boot 3.5.16, Spring Cloud 2025.0.3, Spring Cloud Alibaba 2025.0.0.0, Spring Security, Redis, MyBatis-Plus 3.5.17, MySQL 8.4, Flyway, JUnit 5, Mockito, Testcontainers.

---

## Scope and File Map

This plan delivers only Plan 1 in `2026-09-09-knowledge-platform-implementation-roadmap.md`. Ingestion, OCR, vector indexes, retrieval, management UI and Agent integration remain in Plans 2–6.

```text
order-logistics-knowledge-service/
├─ pom.xml
├─ .gitignore
├─ src/main/java/com/xjjk/knowledge/
│  ├─ KnowledgeServiceApplication.java
│  ├─ common/                         # response and error contracts
│  ├─ auth/                           # SSPX clients, sessions and web security
│  ├─ tenant/TenantAccessGuard.java   # tenant management boundary
│  ├─ audit/                          # allow-listed audit events
│  └─ knowledgebase/                  # aggregate, persistence, service and API
├─ src/main/resources/
│  ├─ application.yml
│  ├─ application-local.yml
│  └─ db/migration/V1__create_knowledge_base_foundation.sql
├─ src/test/java/com/xjjk/knowledge/
└─ docs/local-foundation-runbook.md
```

### Task 1: Scaffold the Knowledge Service

**Files:**
- Create: `D:\GitCode\order-logistics-knowledge-service\pom.xml`
- Create: `D:\GitCode\order-logistics-knowledge-service\.gitignore`
- Create: `D:\GitCode\order-logistics-knowledge-service\src\main\java\com\xjjk\knowledge\KnowledgeServiceApplication.java`
- Create: `D:\GitCode\order-logistics-knowledge-service\src\main\resources\application.yml`
- Create: `D:\GitCode\order-logistics-knowledge-service\src\main\resources\application-local.yml`
- Test: `D:\GitCode\order-logistics-knowledge-service\src\test\java\com\xjjk\knowledge\KnowledgeServiceApplicationTest.java`

- [ ] **Step 1: Initialize the repository**

```powershell
New-Item -ItemType Directory -Path D:\GitCode\order-logistics-knowledge-service
git -C D:\GitCode\order-logistics-knowledge-service init -b main
```

Expected: an empty Git repository on branch `main`.

- [ ] **Step 2: Write the failing context test**

```java
package com.xjjk.knowledge;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.cloud.nacos.config.enabled=false",
        "spring.flyway.enabled=false",
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration"
})
class KnowledgeServiceApplicationTest {
    @Test
    void contextLoads() {
    }
}
```

- [ ] **Step 3: Verify the test cannot build yet**

Run: `mvn test -Dtest=KnowledgeServiceApplicationTest`

Expected: FAIL because `pom.xml` and the application class are absent.

- [ ] **Step 4: Add the Maven build and application**

Use the same platform versions as Agent and include these dependencies:

```xml
<dependencies>
  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-validation</artifactId></dependency>
  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-security</artifactId></dependency>
  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-actuator</artifactId></dependency>
  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-redis</artifactId></dependency>
  <dependency><groupId>com.baomidou</groupId><artifactId>mybatis-plus-spring-boot3-starter</artifactId><version>3.5.17</version></dependency>
  <dependency><groupId>com.mysql</groupId><artifactId>mysql-connector-j</artifactId><scope>runtime</scope></dependency>
  <dependency><groupId>org.flywaydb</groupId><artifactId>flyway-core</artifactId></dependency>
  <dependency><groupId>org.flywaydb</groupId><artifactId>flyway-mysql</artifactId></dependency>
  <dependency><groupId>com.alibaba.cloud</groupId><artifactId>spring-cloud-starter-alibaba-nacos-config</artifactId></dependency>
  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
  <dependency><groupId>org.springframework.security</groupId><artifactId>spring-security-test</artifactId><scope>test</scope></dependency>
  <dependency><groupId>org.testcontainers</groupId><artifactId>mysql</artifactId><scope>test</scope></dependency>
</dependencies>
```

```java
package com.xjjk.knowledge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class KnowledgeServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(KnowledgeServiceApplication.class, args);
    }
}
```

```yaml
spring:
  application:
    name: order-logistics-knowledge-service
  config:
    import: optional:nacos:order-logistics-knowledge.yml?group=DEFAULT_GROUP&refreshEnabled=true
server:
  port: ${KNOWLEDGE_SERVER_PORT:8084}
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
```

- [ ] **Step 5: Run the context test**

Run: `mvn test -Dtest=KnowledgeServiceApplicationTest`

Expected: PASS without contacting Nacos, MySQL or Redis.

Add this exact `.gitignore` before committing:

```gitignore
target/
.idea/
*.iml
.env
.env.*
!.env.example
```

- [ ] **Step 6: Commit**

```powershell
git add pom.xml .gitignore src
git commit -m "chore: scaffold knowledge service"
```

### Task 2: Add stable API errors

**Files:**
- Create: `src/main/java/com/xjjk/knowledge/common/api/ApiResponse.java`
- Create: `src/main/java/com/xjjk/knowledge/common/api/ApiErrorCode.java`
- Create: `src/main/java/com/xjjk/knowledge/common/error/BusinessException.java`
- Create: `src/main/java/com/xjjk/knowledge/common/error/GlobalExceptionHandler.java`
- Test: `src/test/java/com/xjjk/knowledge/common/error/GlobalExceptionHandlerTest.java`

- [ ] **Step 1: Write a failing MVC error test**

```java
mvc.perform(get("/test/failure"))
   .andExpect(status().isForbidden())
   .andExpect(jsonPath("$.code").value("KNOWLEDGE_ACCESS_DENIED"))
   .andExpect(jsonPath("$.data").doesNotExist());
```

- [ ] **Step 2: Run it**

Run: `mvn test -Dtest=GlobalExceptionHandlerTest`

Expected: FAIL because contracts do not exist.

- [ ] **Step 3: Implement response and codes**

```java
public record ApiResponse<T>(String code, String message, T data, OffsetDateTime timestamp) {
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("SUCCESS", "success", data,
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")));
    }
    public static ApiResponse<Void> failure(ApiErrorCode code) {
        return new ApiResponse<>(code.code(), code.message(), null,
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")));
    }
}
```

Define these error codes and HTTP statuses:

```text
AUTH_REQUIRED                  401
AUTH_INVALID                   401
AUTH_SERVICE_UNAVAILABLE       503
KNOWLEDGE_ACCESS_DENIED        403
TENANT_ACCESS_DENIED           403
KNOWLEDGE_BASE_NOT_FOUND       404
KNOWLEDGE_BASE_NAME_CONFLICT   409
KNOWLEDGE_BASE_VERSION_CONFLICT 409
VALIDATION_FAILED              400
INTERNAL_ERROR                 500
```

The exception advice returns JSON for business, validation and unexpected failures. Unexpected failures log request ID and stack trace, never headers, cookies or request bodies.

- [ ] **Step 4: Run the test**

Run: `mvn test -Dtest=GlobalExceptionHandlerTest`

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/xjjk/knowledge/common src/test/java/com/xjjk/knowledge/common
git commit -m "feat: add knowledge api error contract"
```

### Task 3: Create the foundation schema

**Files:**
- Create: `src/main/resources/db/migration/V1__create_knowledge_base_foundation.sql`
- Test: `src/test/java/com/xjjk/knowledge/persistence/FoundationMigrationTest.java`

- [ ] **Step 1: Write a failing MySQL Testcontainers test**

Run Flyway against `mysql:8.4` and assert:

```java
assertThat(tableNames).contains(
        "flyway_schema_history", "kb_knowledge_base", "kb_audit_log");
```

- [ ] **Step 2: Verify failure**

Run: `mvn test -Dtest=FoundationMigrationTest`

Expected: FAIL because migration V1 is absent.

- [ ] **Step 3: Add the exact SQL**

```sql
CREATE TABLE kb_knowledge_base (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  name VARCHAR(100) NOT NULL,
  description VARCHAR(500) NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'ENABLED',
  created_by BIGINT NOT NULL,
  updated_by BIGINT NOT NULL,
  row_version INT NOT NULL DEFAULT 0,
  is_deleted TINYINT(1) NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_kb_tenant_name (tenant_id, name),
  KEY idx_kb_tenant_status (tenant_id, status, is_deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE kb_audit_log (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  actor_user_id BIGINT NOT NULL,
  actor_tenant_id BIGINT NOT NULL,
  action VARCHAR(60) NOT NULL,
  resource_type VARCHAR(40) NOT NULL,
  resource_id VARCHAR(80) NOT NULL,
  request_id VARCHAR(64) NOT NULL,
  outcome VARCHAR(20) NOT NULL,
  detail_json JSON NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_audit_tenant_created (tenant_id, created_at),
  KEY idx_audit_actor_created (actor_user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
```

The unique key intentionally reserves names after soft deletion; restoration or rename releases them explicitly and preserves unambiguous history.

- [ ] **Step 4: Run the migration test**

Run: `mvn test -Dtest=FoundationMigrationTest`

Expected: PASS with Flyway schema version `1`.

- [ ] **Step 5: Commit**

```powershell
git add src/main/resources/db/migration src/test/java/com/xjjk/knowledge/persistence
git commit -m "feat: add knowledge foundation schema"
```

### Task 4: Implement SSPX clients

**Files:**
- Create: `src/main/java/com/xjjk/knowledge/auth/config/SspxProperties.java`
- Create: `src/main/java/com/xjjk/knowledge/auth/client/SspxOAuthClient.java`
- Create: `src/main/java/com/xjjk/knowledge/auth/client/SspxIdentityClient.java`
- Create: `src/main/java/com/xjjk/knowledge/auth/client/dto/SspxTokenResponse.java`
- Create: `src/main/java/com/xjjk/knowledge/auth/client/dto/SspxCurrentUserEnvelope.java`
- Create: `src/main/java/com/xjjk/knowledge/auth/client/dto/SspxAjaxEnvelope.java`
- Create: `src/main/java/com/xjjk/knowledge/auth/client/dto/SspxCurrentUserPayload.java`
- Create: `src/main/java/com/xjjk/knowledge/auth/client/dto/SspxRolePayload.java`
- Test: `src/test/java/com/xjjk/knowledge/auth/client/SspxClientTest.java`

- [ ] **Step 1: Write failing HTTP contract tests**

Use `MockRestServiceServer` and verify password-grant fields, current-user hex decoding and role codes:

```java
assertThat(currentUser.id()).isEqualTo(10567L);
assertThat(roles).extracting(SspxRolePayload::code)
        .containsExactly("KNOWLEDGE_ADMIN");
```

Also cover malformed hex, non-1000 envelope codes, missing fields, 4xx and 5xx.

- [ ] **Step 2: Run tests**

Run: `mvn test -Dtest=SspxClientTest`

Expected: FAIL because clients are absent.

- [ ] **Step 3: Implement immutable configuration and DTOs**

```java
@ConfigurationProperties("knowledge.sspx")
@Validated
public record SspxProperties(
        @NotBlank String baseUrl,
        @NotBlank String clientId,
        @NotBlank String clientSecret,
        @Positive long applicationId
) {}

public record SspxRolePayload(
        Long applicationId, Long companyId, String code,
        Integer status, Boolean isDeleted
) {}
```

The OAuth client posts to `/oauth2/token`. The identity client calls `/authorizationcenter/user/current`, requires its success code `1000`, and hex-decodes its JSON. The role client then calls `/SysOpenUserRole/getUserRoles?applicationId=444&userId={userId}` with the bearer token and requires the SSPX `AjaxJson` success code `200`. Keep these two response envelopes separate because their success codes are intentionally different.

- [ ] **Step 4: Implement strict role validation**

```java
boolean valid = Long.valueOf(properties.applicationId()).equals(role.applicationId())
        && Integer.valueOf(1).equals(role.status())
        && !Boolean.TRUE.equals(role.isDeleted())
        && Set.of("KNOWLEDGE_ADMIN", "KNOWLEDGE_SUPER_ADMIN").contains(role.code());
```

Never fall back to Chinese names or role IDs 244/245. Never log password or tokens.

- [ ] **Step 5: Run tests**

Run: `mvn test -Dtest=SspxClientTest`

Expected: PASS with stable invalid-response and unavailable-service assertions.

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/com/xjjk/knowledge/auth/config src/main/java/com/xjjk/knowledge/auth/client src/test/java/com/xjjk/knowledge/auth/client
git commit -m "feat: add sspx admin identity clients"
```

### Task 5: Add opaque Redis admin sessions

**Files:**
- Create: `src/main/java/com/xjjk/knowledge/auth/domain/KnowledgeRole.java`
- Create: `src/main/java/com/xjjk/knowledge/auth/domain/AdminPrincipal.java`
- Create: `src/main/java/com/xjjk/knowledge/auth/session/AdminSession.java`
- Create: `src/main/java/com/xjjk/knowledge/auth/session/AdminSessionProperties.java`
- Create: `src/main/java/com/xjjk/knowledge/auth/session/AdminSessionRepository.java`
- Create: `src/main/java/com/xjjk/knowledge/auth/session/RedisAdminSessionRepository.java`
- Test: `src/test/java/com/xjjk/knowledge/auth/session/RedisAdminSessionRepositoryTest.java`

- [ ] **Step 1: Write failing session tests**

```java
String browserToken = repository.create(session);
assertThat(browserToken).hasSizeGreaterThanOrEqualTo(43);
assertThat(redisValue).doesNotContain(browserToken);
assertThat(repository.find(browserToken)).contains(session);
repository.delete(browserToken);
assertThat(repository.find(browserToken)).isEmpty();
```

Also advance a test clock and assert the 30-minute idle and 8-hour absolute expirations.

- [ ] **Step 2: Run tests**

Run: `mvn test -Dtest=RedisAdminSessionRepositoryTest`

Expected: FAIL because session types are absent.

- [ ] **Step 3: Implement roles and principal**

```java
public enum KnowledgeRole {
    KNOWLEDGE_ADMIN,
    KNOWLEDGE_SUPER_ADMIN
}

public record AdminPrincipal(
        long userId,
        String account,
        String displayName,
        long tenantId,
        Set<KnowledgeRole> roles
) {
    public boolean isSuperAdmin() {
        return roles.contains(KnowledgeRole.KNOWLEDGE_SUPER_ADMIN);
    }
}
```

`AdminSession` additionally stores the SSPX access/refresh token, token expiry, creation time, last-access time and `rolesVerifiedAt`. Those values never leave the service.

- [ ] **Step 4: Implement Redis storage**

Generate 32 random bytes with `SecureRandom` and return Base64 URL without padding. Store session JSON under the SHA-256 digest, not the raw browser token:

```text
kb:admin:session:{sha256Hex}
```

Use a 30-minute sliding idle TTL and reject sessions older than 8 hours. Persist a new last-access time only after 60 seconds to avoid one Redis write per request.

- [ ] **Step 5: Run tests**

Run: `mvn test -Dtest=RedisAdminSessionRepositoryTest`

Expected: PASS and captured logs contain no raw session token.

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/com/xjjk/knowledge/auth/domain src/main/java/com/xjjk/knowledge/auth/session src/test/java/com/xjjk/knowledge/auth/session
git commit -m "feat: add redis admin sessions"
```

### Task 6: Add login, logout and current-user APIs

**Files:**
- Create: `src/main/java/com/xjjk/knowledge/auth/service/AdminLoginService.java`
- Create: `src/main/java/com/xjjk/knowledge/auth/web/AuthController.java`
- Create: `src/main/java/com/xjjk/knowledge/auth/web/dto/LoginRequest.java`
- Create: `src/main/java/com/xjjk/knowledge/auth/web/dto/AdminIdentityResponse.java`
- Test: `src/test/java/com/xjjk/knowledge/auth/service/AdminLoginServiceTest.java`
- Test: `src/test/java/com/xjjk/knowledge/auth/web/AuthControllerTest.java`

- [ ] **Step 1: Write failing login tests**

```java
assertThat(service.login("74680", "secret").principal().roles())
        .containsExactly(KnowledgeRole.KNOWLEDGE_ADMIN);
assertThatThrownBy(() -> service.login("ordinary", "secret"))
        .isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.errorCode())
                        .isEqualTo(ApiErrorCode.KNOWLEDGE_ACCESS_DENIED));
```

Cover super admin, both roles, disabled role, deleted role, invalid password and SSPX outage.

- [ ] **Step 2: Run tests**

Run: `mvn test -Dtest=AdminLoginServiceTest,AuthControllerTest`

Expected: FAIL because the login flow is absent.

- [ ] **Step 3: Implement login orchestration**

Execute in this order:

```text
password grant
→ validate current user
→ query and validate app-444 roles
→ reject users without a valid knowledge-admin role
→ create Redis session
→ return non-sensitive principal
```

Do not create a session before identity and role validation both succeed.

- [ ] **Step 4: Implement cookie endpoints**

```text
GET  /api/v1/admin/auth/csrf
POST /api/v1/admin/auth/login
GET  /api/v1/admin/auth/me
POST /api/v1/admin/auth/logout
```

Cookie `KB_ADMIN_SESSION` is `HttpOnly`, `SameSite=Lax`, path `/`, configurable `Secure`, and expires no later than the absolute session lifetime. Login data is exactly:

```json
{
  "userId": 10567,
  "account": "74680",
  "displayName": "石海文",
  "tenantId": 1,
  "roles": ["KNOWLEDGE_ADMIN"]
}
```

- [ ] **Step 5: Run tests**

Run: `mvn test -Dtest=AdminLoginServiceTest,AuthControllerTest`

Expected: PASS; no access token, refresh token or client secret appears in JSON.

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/com/xjjk/knowledge/auth/service src/main/java/com/xjjk/knowledge/auth/web src/test/java/com/xjjk/knowledge/auth
git commit -m "feat: add knowledge admin login"
```

### Task 7: Enforce session security and tenant access

**Files:**
- Create: `src/main/java/com/xjjk/knowledge/auth/service/AdminRoleRefresher.java`
- Create: `src/main/java/com/xjjk/knowledge/auth/web/AdminSessionFilter.java`
- Create: `src/main/java/com/xjjk/knowledge/auth/web/SecurityConfiguration.java`
- Create: `src/main/java/com/xjjk/knowledge/tenant/TenantAccessGuard.java`
- Test: `src/test/java/com/xjjk/knowledge/auth/web/AdminSecurityTest.java`
- Test: `src/test/java/com/xjjk/knowledge/tenant/TenantAccessGuardTest.java`

- [ ] **Step 1: Write failing authorization tests**

```java
assertThatCode(() -> guard.requireManage(systemAdmin, 1L)).doesNotThrowAnyException();
assertThatThrownBy(() -> guard.requireManage(systemAdmin, 2L))
        .isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.errorCode())
                        .isEqualTo(ApiErrorCode.TENANT_ACCESS_DENIED));
assertThatCode(() -> guard.requireManage(superAdmin, 2L)).doesNotThrowAnyException();
```

MVC tests verify missing/invalid sessions return `401`, missing CSRF returns `403`, and an authenticated GET succeeds. Advance the test clock beyond five minutes, disable the role in the mocked SSPX response, and assert the next request returns `403` and deletes the session.

- [ ] **Step 2: Run tests**

Run: `mvn test -Dtest=AdminSecurityTest,TenantAccessGuardTest`

Expected: FAIL because filter and guard are absent.

- [ ] **Step 3: Implement the guard**

```java
public void requireManage(AdminPrincipal principal, long targetTenantId) {
    if (targetTenantId <= 0) {
        throw new BusinessException(ApiErrorCode.VALIDATION_FAILED);
    }
    if (!principal.isSuperAdmin() && principal.tenantId() != targetTenantId) {
        throw new BusinessException(ApiErrorCode.TENANT_ACCESS_DENIED);
    }
}
```

- [ ] **Step 4: Implement security**

`AdminSessionFilter` reads only `KB_ADMIN_SESSION`, resolves Redis state and creates the authenticated `AdminPrincipal`. It ignores browser-provided identity/tenant/role headers. `AdminRoleRefresher` re-queries Application ID 444 roles whenever `rolesVerifiedAt` is older than five minutes; an invalid role or SSPX failure rejects the management request, and a user with no remaining management role has the session deleted. Future publish/delete/rollback actions bypass the five-minute cache and force refresh.

`SecurityConfiguration` permits health, CSRF bootstrap and login; authenticates every other `/api/v1/admin/**`; uses `CookieCsrfTokenRepository` with `XSRF-TOKEN` and `X-XSRF-TOKEN`; returns JSON for `401/403`; and denies `/internal/**` until Plan 3 adds explicit service authentication.

- [ ] **Step 5: Run tests**

Run: `mvn test -Dtest=AdminSecurityTest,TenantAccessGuardTest`

Expected: PASS for cookie, CSRF, role and cross-tenant cases.

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/com/xjjk/knowledge/auth/web src/main/java/com/xjjk/knowledge/tenant src/test/java/com/xjjk/knowledge/auth/web src/test/java/com/xjjk/knowledge/tenant
git commit -m "feat: enforce admin session security"
```

### Task 8: Add tenant-safe knowledge-base persistence

**Files:**
- Create: `src/main/java/com/xjjk/knowledge/knowledgebase/domain/KnowledgeBaseStatus.java`
- Create: `src/main/java/com/xjjk/knowledge/knowledgebase/domain/KnowledgeBase.java`
- Create: `src/main/java/com/xjjk/knowledge/knowledgebase/persistence/KnowledgeBaseEntity.java`
- Create: `src/main/java/com/xjjk/knowledge/knowledgebase/persistence/KnowledgeBaseMapper.java`
- Create: `src/main/java/com/xjjk/knowledge/knowledgebase/persistence/KnowledgeBaseRepository.java`
- Create: `src/main/java/com/xjjk/knowledge/knowledgebase/persistence/MybatisKnowledgeBaseRepository.java`
- Test: `src/test/java/com/xjjk/knowledge/knowledgebase/persistence/KnowledgeBaseRepositoryIntegrationTest.java`

- [ ] **Step 1: Write failing tenant tests**

```java
assertThat(repository.findById(tenantOneId, knowledgeBaseId)).isPresent();
assertThat(repository.findById(tenantTwoId, knowledgeBaseId)).isEmpty();
assertThat(repository.list(tenantOneId)).extracting(KnowledgeBase::tenantId)
        .containsOnly(tenantOneId);
```

Also assert optimistic-lock conflicts and exclusion of soft-deleted rows.

- [ ] **Step 2: Run tests**

Run: `mvn test -Dtest=KnowledgeBaseRepositoryIntegrationTest`

Expected: FAIL because persistence is absent.

- [ ] **Step 3: Add the aggregate and repository contract**

```java
public record KnowledgeBase(
        long id, long tenantId, String name, String description,
        KnowledgeBaseStatus status, int rowVersion,
        OffsetDateTime createdAt, OffsetDateTime updatedAt
) {}
```

```java
KnowledgeBase create(long tenantId, long actorUserId, String name, String description);
Optional<KnowledgeBase> findById(long tenantId, long knowledgeBaseId);
List<KnowledgeBase> list(long tenantId);
KnowledgeBase update(long tenantId, long knowledgeBaseId, int expectedVersion,
                     String name, String description, long actorUserId);
KnowledgeBase setStatus(long tenantId, long knowledgeBaseId,
                        KnowledgeBaseStatus status, long actorUserId);
void softDelete(long tenantId, long knowledgeBaseId, long actorUserId);
```

- [ ] **Step 4: Implement mandatory predicates**

Every select and update includes `tenant_id = :tenantId AND is_deleted = 0`. Updates also match `row_version` and increment it atomically. Map duplicate names to `KNOWLEDGE_BASE_NAME_CONFLICT` and stale writes to `KNOWLEDGE_BASE_VERSION_CONFLICT`.

- [ ] **Step 5: Run tests**

Run: `mvn test -Dtest=KnowledgeBaseRepositoryIntegrationTest`

Expected: PASS for tenant, deletion, unique-name and optimistic-lock cases.

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/com/xjjk/knowledge/knowledgebase src/test/java/com/xjjk/knowledge/knowledgebase
git commit -m "feat: add tenant safe knowledge base persistence"
```

### Task 9: Add audited application services

**Files:**
- Create: `src/main/java/com/xjjk/knowledge/audit/AuditAction.java`
- Create: `src/main/java/com/xjjk/knowledge/audit/AuditEvent.java`
- Create: `src/main/java/com/xjjk/knowledge/audit/AuditMapper.java`
- Create: `src/main/java/com/xjjk/knowledge/audit/AuditService.java`
- Create: `src/main/java/com/xjjk/knowledge/knowledgebase/service/KnowledgeBaseService.java`
- Test: `src/test/java/com/xjjk/knowledge/knowledgebase/service/KnowledgeBaseServiceTest.java`

- [ ] **Step 1: Write failing service tests**

```java
service.create(principal, 1L, "售后规则", "退款与换货政策", "req-1");
verify(guard).requireManage(principal, 1L);
verify(auditService).success(
        eq(1L), eq(principal), eq(AuditAction.KNOWLEDGE_BASE_CREATE),
        anyString(), eq("req-1"), anyMap());
```

Assert a cross-tenant request neither calls the repository nor writes a success audit.

- [ ] **Step 2: Run tests**

Run: `mvn test -Dtest=KnowledgeBaseServiceTest`

Expected: FAIL because services are absent.

- [ ] **Step 3: Implement audit and application services**

Audit JSON contains only allow-listed metadata:

```json
{
  "knowledgeBaseName": "售后规则",
  "previousStatus": "ENABLED",
  "newStatus": "DISABLED"
}
```

Never serialize incoming request objects wholesale. Create, update, enable, disable and delete are transactional so the metadata change and success audit commit together. Authorization always runs before repository access.

- [ ] **Step 4: Run tests**

Run: `mvn test -Dtest=KnowledgeBaseServiceTest`

Expected: PASS with authorization-before-persistence verified.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/xjjk/knowledge/audit src/main/java/com/xjjk/knowledge/knowledgebase/service src/test/java/com/xjjk/knowledge/knowledgebase/service
git commit -m "feat: add audited knowledge base service"
```

### Task 10: Expose tenant-explicit management APIs

**Files:**
- Create: `src/main/java/com/xjjk/knowledge/knowledgebase/web/KnowledgeBaseController.java`
- Create: `src/main/java/com/xjjk/knowledge/knowledgebase/web/dto/CreateKnowledgeBaseRequest.java`
- Create: `src/main/java/com/xjjk/knowledge/knowledgebase/web/dto/UpdateKnowledgeBaseRequest.java`
- Create: `src/main/java/com/xjjk/knowledge/knowledgebase/web/dto/KnowledgeBaseResponse.java`
- Test: `src/test/java/com/xjjk/knowledge/knowledgebase/web/KnowledgeBaseControllerTest.java`

- [ ] **Step 1: Write failing MVC contract tests**

Test these routes:

```text
GET    /api/v1/admin/tenants/{tenantId}/knowledge-bases
POST   /api/v1/admin/tenants/{tenantId}/knowledge-bases
GET    /api/v1/admin/tenants/{tenantId}/knowledge-bases/{knowledgeBaseId}
PUT    /api/v1/admin/tenants/{tenantId}/knowledge-bases/{knowledgeBaseId}
POST   /api/v1/admin/tenants/{tenantId}/knowledge-bases/{knowledgeBaseId}/enable
POST   /api/v1/admin/tenants/{tenantId}/knowledge-bases/{knowledgeBaseId}/disable
DELETE /api/v1/admin/tenants/{tenantId}/knowledge-bases/{knowledgeBaseId}
```

Assert validation, response fields, CSRF, request ID propagation and tenant access.

- [ ] **Step 2: Run tests**

Run: `mvn test -Dtest=KnowledgeBaseControllerTest`

Expected: FAIL because controller and DTOs are absent.

- [ ] **Step 3: Add validated DTOs**

```java
public record CreateKnowledgeBaseRequest(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 500) String description
) {}

public record UpdateKnowledgeBaseRequest(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 500) String description,
        @PositiveOrZero int expectedVersion
) {}
```

Trim surrounding whitespace in the service and reject blank values; never silently truncate.

- [ ] **Step 4: Implement the controller**

Resolve `AdminPrincipal` from Spring Security, pass path `tenantId` through `TenantAccessGuard`, and return `ApiResponse<KnowledgeBaseResponse>`. Generate `X-Request-Id` when absent, return it in the response, and write the same value to audit records.

- [ ] **Step 5: Run tests**

Run: `mvn test -Dtest=KnowledgeBaseControllerTest,KnowledgeBaseServiceTest`

Expected: PASS; cross-tenant system-admin calls return `403`, while explicit super-admin calls succeed.

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/com/xjjk/knowledge/knowledgebase/web src/test/java/com/xjjk/knowledge/knowledgebase/web
git commit -m "feat: expose knowledge base admin api"
```

### Task 11: Finish local configuration and the gateway runbook

**Files:**
- Modify: `src/main/resources/application-local.yml`
- Create: `docs/local-foundation-runbook.md`
- Test: `src/test/java/com/xjjk/knowledge/config/ConfigurationContractTest.java`

- [ ] **Step 1: Write a failing secret/configuration test**

```java
assertThat(localYaml).contains("client-secret: ${SSPX_CLIENT_SECRET}");
assertThat(localYaml).doesNotMatch(
        "(?s).*client-secret:\\s*[A-Fa-f0-9]{32,}.*");
assertThat(baseYaml).contains("port: ${KNOWLEDGE_SERVER_PORT:8084}");
```

- [ ] **Step 2: Run tests**

Run: `mvn test -Dtest=ConfigurationContractTest`

Expected: FAIL until all settings are present.

- [ ] **Step 3: Complete safe local settings**

```yaml
spring:
  datasource:
    url: ${KNOWLEDGE_DB_URL:jdbc:mysql://127.0.0.1:3307/order_logistics_knowledge?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai}
    username: ${KNOWLEDGE_DB_USERNAME:root}
    password: ${KNOWLEDGE_DB_PASSWORD:root}
  data:
    redis:
      host: ${KNOWLEDGE_REDIS_HOST:127.0.0.1}
      port: ${KNOWLEDGE_REDIS_PORT:6380}
knowledge:
  sspx:
    base-url: ${SSPX_BASE_URL:http://127.0.0.1:8080}
    client-id: ${SSPX_CLIENT_ID:100006}
    client-secret: ${SSPX_CLIENT_SECRET}
    application-id: ${SSPX_APPLICATION_ID:444}
    connect-timeout: 2s
    read-timeout: 4s
  admin-session:
    cookie-name: KB_ADMIN_SESSION
    idle-timeout: 30m
    absolute-timeout: 8h
    secure-cookie: ${KNOWLEDGE_SECURE_COOKIE:false}
```

- [ ] **Step 4: Write the runbook**

Include the exact database command:

```sql
CREATE DATABASE IF NOT EXISTS order_logistics_knowledge
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
```

Include local startup using a terminal-only secret:

```powershell
$env:SPRING_PROFILES_ACTIVE='local'
$env:SSPX_CLIENT_SECRET = Read-Host -MaskInput 'SSPX Client Secret'
mvn spring-boot:run
```

Document the route without an environment-specific hostname:

```text
Path: /knowledge/**
Rewrite: remove /knowledge prefix
Target: http://127.0.0.1:8084
```

Add PowerShell examples for CSRF bootstrap, login, `me`, create/list knowledge bases and logout. Use dummy credentials only.

- [ ] **Step 5: Run tests**

Run: `mvn test -Dtest=ConfigurationContractTest`

Expected: PASS and tracked files contain no real secret.

- [ ] **Step 6: Commit**

```powershell
git add src/main/resources/application-local.yml docs/local-foundation-runbook.md src/test/java/com/xjjk/knowledge/config
git commit -m "docs: add knowledge foundation runbook"
```

### Task 12: Run the foundation verification gate

**Files:**
- Modify only files required to correct failures found by this gate.

- [ ] **Step 1: Compile from a clean target directory**

Run: `mvn -DskipTests clean compile`

Expected: `BUILD SUCCESS`.

- [ ] **Step 2: Run all tests**

Run: `mvn test`

Expected: `BUILD SUCCESS` with no failed foundation tests.

- [ ] **Step 3: Run MySQL tests explicitly**

Run: `mvn test -Dtest=FoundationMigrationTest,KnowledgeBaseRepositoryIntegrationTest`

Expected: both tests PASS against MySQL 8.4.

- [ ] **Step 4: Scan for forbidden secrets**

```powershell
git grep -n -E "client-secret:[[:space:]]*[A-Fa-f0-9]{32,}"
```

Expected: no output.

- [ ] **Step 5: Inspect commits and repository state**

```powershell
git status --short
git log --oneline -12
```

Expected: clean working tree and small commits matching Tasks 1–11. If the gate required a repair, stage only the repaired files and commit `fix: correct knowledge foundation verification` before completion.

## Completion Output

Report the Knowledge Service commit range, exact verification commands and results, local route, required environment-variable names without values, and confirmation that pre-existing changes in other repositories were not included.
