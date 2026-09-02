# Desktop Password Login Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the desktop demo identity with real SSPX username/password login, encrypted token persistence, automatic session restoration, and authenticated tenant derivation.

**Architecture:** Electron's main process sends credentials to an anonymous Agent login facade. Agent uses server-side OAuth client credentials to obtain SSPX tokens, validates the new access token through the existing current-user endpoint, maps SSPX `companyId` to Agent `tenantId`, and returns a normalized session. Electron encrypts the token bundle with `safeStorage`; Vue only receives token-free identity state.

**Tech Stack:** Java 21, Spring Boot 3.5, RestClient, Jakarta Validation, JUnit 5, Electron 44, Vue 3, TypeScript, Vitest

---

### Task 1: Derive tenant from authenticated identity

**Files:**
- Modify: `src/main/java/com/xjjk/agent/identity/domain/AgentIdentity.java`
- Modify: `src/main/java/com/xjjk/agent/identity/service/SspxAuthenticationService.java`
- Modify: `src/main/java/com/xjjk/agent/identity/web/SspxAuthenticationInterceptor.java`
- Modify: `src/main/java/com/xjjk/agent/tenant/web/TenantIdArgumentResolver.java`
- Modify: `src/main/java/com/xjjk/agent/tenant/config/TenantWebMvcConfiguration.java`
- Modify: `src/main/java/com/xjjk/agent/system/api/SystemController.java`
- Modify: `src/main/java/com/xjjk/agent/system/api/dto/CurrentIdentityResponse.java`
- Modify: `src/main/java/com/xjjk/agent/system/api/dto/PingResponse.java`
- Delete: `src/main/java/com/xjjk/agent/tenant/web/TenantInterceptor.java`
- Delete: `src/main/java/com/xjjk/agent/tenant/config/TenantProperties.java`
- Test: `src/test/java/com/xjjk/agent/identity/service/SspxAuthenticationServiceTest.java`

- [ ] Write a failing test asserting `SspxCurrentUserPayload.CompanyId` becomes `AgentIdentity.tenantId()`.
- [ ] Run `mvn -Dtest=SspxAuthenticationServiceTest test` and confirm the old `companyId()` contract fails compilation.
- [ ] Rename the Agent domain field and make the authentication interceptor place both identity and `tenantId` into request attributes.
- [ ] Remove header parsing and make `@CurrentTenantId` resolve only the authenticated request attribute.
- [ ] Rename system response fields to `tenantId` and run the focused test.
- [ ] Commit with `refactor: derive tenant from authenticated identity`.

### Task 2: Add SSPX OAuth token adapter

**Files:**
- Create: `src/main/java/com/xjjk/agent/auth/config/SspxOAuthProperties.java`
- Create: `src/main/java/com/xjjk/agent/auth/config/SspxOAuthConfiguration.java`
- Create: `src/main/java/com/xjjk/agent/auth/client/SspxOAuthClient.java`
- Create: `src/main/java/com/xjjk/agent/auth/client/dto/SspxTokenResponse.java`
- Create: `src/main/java/com/xjjk/agent/auth/client/SspxOAuthClientException.java`
- Test: `src/test/java/com/xjjk/agent/auth/client/SspxOAuthClientTest.java`

- [ ] Write tests with a local mock HTTP server for form-encoded password and refresh requests.
- [ ] Verify the first test fails because the adapter does not exist.
- [ ] Implement a `RestClient` adapter that sends credentials in the form body and never retries.
- [ ] Parse access token, refresh token, token type and expiry fields while retaining SSPX error code information for mapping.
- [ ] Run `mvn -Dtest=SspxOAuthClientTest test` and confirm request bodies contain the required fields without query-string secrets.
- [ ] Commit with `feat: add SSPX OAuth token adapter`.

### Task 3: Expose normalized Agent login and refresh APIs

**Files:**
- Create: `src/main/java/com/xjjk/agent/auth/api/AuthController.java`
- Create: `src/main/java/com/xjjk/agent/auth/api/dto/LoginRequest.java`
- Create: `src/main/java/com/xjjk/agent/auth/api/dto/RefreshRequest.java`
- Create: `src/main/java/com/xjjk/agent/auth/api/dto/AuthSessionResponse.java`
- Create: `src/main/java/com/xjjk/agent/auth/api/dto/AuthenticatedUserResponse.java`
- Create: `src/main/java/com/xjjk/agent/auth/service/AuthApplicationService.java`
- Modify: `src/main/java/com/xjjk/agent/common/api/ApiErrorCode.java`
- Modify: `src/main/java/com/xjjk/agent/common/exception/GlobalExceptionHandler.java`
- Modify: `src/main/java/com/xjjk/agent/identity/config/IdentityWebMvcConfiguration.java`
- Test: `src/test/java/com/xjjk/agent/auth/service/AuthApplicationServiceTest.java`

- [ ] Write failing tests for successful login, identity normalization, invalid credentials, unavailable account, timeout, malformed response and refresh failure.
- [ ] Add `AUTH_CREDENTIALS_INVALID` and `AUTH_ACCOUNT_UNAVAILABLE` mappings.
- [ ] Implement login/refresh orchestration: token request, current-user validation, normalized response.
- [ ] Exclude exactly `/api/v1/auth/login` and `/api/v1/auth/refresh` from authentication interception.
- [ ] Add Jakarta validation and map validation failures to `VALIDATION_ERROR`.
- [ ] Run `mvn test` and commit with `feat: add password login facade`.

### Task 4: Document secret injection without storing values

**Files:**
- Modify: `.env.example`
- Modify: `README.md`

- [ ] Add only `SSPX_OAUTH_CLIENT_ID` and `SSPX_OAUTH_CLIENT_SECRET` variable names with blank/example placeholders.
- [ ] Document the matching Nacos placeholders and IDEA environment-variable setup.
- [ ] Search tracked files for the supplied secret value and confirm zero matches without printing it.
- [ ] Commit with `docs: configure SSPX OAuth credentials`.

### Task 5: Add Electron authentication transport and encrypted repository

**Files:**
- Create: `src/main/auth-api-client.ts`
- Create: `src/main/auth-session-repository.ts`
- Modify: `src/main/auth-session.ts`
- Modify: `src/main/index.ts`
- Test: `src/main/auth-api-client.test.ts`
- Test: `src/main/auth-session-repository.test.ts`
- Modify: `src/main/auth-session.test.ts`

- [ ] Write failing tests for login normalization, encrypted write/read, failed-login no-write, restoration, offline preservation and logout cleanup.
- [ ] Implement main-process HTTP calls using non-secret URL configuration.
- [ ] Implement a `safeStorage` abstraction and atomic encrypted session file replacement.
- [ ] Make `AuthSession` asynchronous and ensure public state never includes tokens.
- [ ] Restore the saved session during app startup before broadcasting UI state.
- [ ] Run `npm test -- --run src/main` and commit if the frontend repository is Git-managed; otherwise retain verified workspace changes.

### Task 6: Replace demo IPC contract

**Files:**
- Modify: `src/shared/desktop.ts`
- Modify: `src/preload/index.ts`
- Modify: `src/preload/index.d.ts`
- Modify: `src/main/ipc.ts`
- Modify: `src/renderer/src/stores/auth.ts`

- [ ] Define `LoginCredentials`, token-free `AuthenticatedUser`, and `offline` AuthState.
- [ ] Replace `loginAsDemoUser` with `login(credentials)` across IPC, preload and Pinia.
- [ ] Validate IPC payload shape in the main process before network calls.
- [ ] Ensure only token-free AuthState crosses IPC.
- [ ] Run `npm run typecheck` and resolve all contract mismatches.

### Task 7: Build the real login interface

**Files:**
- Modify: `src/renderer/src/components/LoginWindow.vue`
- Modify: `src/renderer/src/assets/main.css`

- [ ] Replace the demo button with username and password inputs, password visibility control, inline error region and submit form.
- [ ] Disable duplicate submissions and clear the password in `finally`.
- [ ] Keep the existing deer visual and open chat after success.
- [ ] Verify keyboard Enter submission and accessible labels.
- [ ] Run `npm run typecheck`, `npm run lint`, and `npm test`.

### Task 8: End-to-end verification

**Files:**
- Modify: `D:/GitCode/order-logistics-agent-server/docs/superpowers/plans/2026-09-02-desktop-password-login-implementation.md` (check completed steps)

- [ ] Run full backend tests with `mvn test` using non-secret test configuration.
- [ ] Run frontend `npm run build` and `npm test`.
- [ ] Start SSPX, Agent, Gateway and the desktop app with secrets supplied only through the local environment.
- [ ] Log in with a dedicated test account and verify the response/UI exposes `tenantId`, not `companyId`.
- [ ] Restart the desktop app and verify encrypted session restoration.
- [ ] Verify wrong password does not create a session file and logout deletes the local encrypted session.
- [ ] Run `git status --short` in both Git-managed repositories and inspect all diffs for secrets.
