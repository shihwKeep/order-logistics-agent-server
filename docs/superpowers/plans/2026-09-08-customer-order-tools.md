# Customer And Customer Order Tools Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add production-safe customer search and customer-order query tools, a recoverable customer card, and a deterministic “view orders” action without exposing arbitrary customer IDs to the model.

**Architecture:** `customerservice` owns customer authorization and exact lookup, `order` owns customer-order authorization and rich order aggregation, and the Agent orchestrates both through authenticated internal endpoints. The desktop renders `customer-list` results and reuses the existing `order-list` card after a deterministic action.

**Tech Stack:** Java, Spring Boot, OpenFeign, MyBatis, Spring Cloud CircuitBreaker/Resilience4j, Spring AI, MySQL message-result persistence, Vue 3, TypeScript, Vitest, Maven.

---

## Working-tree safety

The repositories contain unrelated or partially staged work. Before every commit, inspect `git status --short` and use `git commit --only -- <exact paths>`. Do not reset, checkout, clean, stash, amend, or include unrelated changes.

## File map

### `D:\GitCode\customerservice`

- Create `src/main/java/com/xjjk/cs/agent/config/*`: validated internal API settings and web registration.
- Create `src/main/java/com/xjjk/cs/agent/security/*`: internal Token validation and trusted request identity.
- Create `src/main/java/com/xjjk/cs/agent/api/*`: match type, request, safe response and controller.
- Create `src/main/java/com/xjjk/cs/agent/domain/*`: immutable customer access scope.
- Create `src/main/java/com/xjjk/cs/agent/dao/*`: minimal row, parameters and exact scoped SQL.
- Create `src/main/java/com/xjjk/cs/agent/service/*`: access resolver, masking and query orchestration.
- Add focused tests under `src/test/java/com/xjjk/cs/agent/**`.

### `D:\GitCode\order`

- Create `src/main/java/com/xjjk/ec/oms/agent/api/AgentCustomerOrderRequest.java`.
- Modify `src/main/java/com/xjjk/ec/oms/agent/api/AgentOrderIdentifierType.java`: add the explicit `CUSTOMER` query source.
- Modify `AgentOrderController`, `AgentOrderQueryMapper`, `AgentOrderQueryParam` and `AgentOrderQueryService`.
- Extend the existing Agent controller, mapper and service tests.

### `D:\GitCode\order-logistics-agent-server`

- Create `src/main/java/com/xjjk/agent/customer/**`: domain, client, gateway, resilience, availability and tool.
- Modify `src/main/java/com/xjjk/agent/order/domain/OrderIdentifierType.java`: accept the downstream `CUSTOMER` query source.
- Extend the existing order client/gateway/tool for trusted customer-order lookup.
- Modify `AiChatService`, deterministic action classes and result-history schema whitelist.
- Add focused unit and contract tests.

### `D:\GitCode\order-logistics-agent-web`

- Modify `contracts/chat.ts`, `stores/chat-accumulator.ts` and `ChatWindow.vue`.
- Create `CustomerListCard.vue` and `CustomerListCard.test.ts`.
- Extend focused contract, accumulator and window tests.

---

### Task 1: Build the fail-closed customer access scope

**Files:**
- Create: `D:\GitCode\customerservice\src\main\java\com\xjjk\cs\agent\domain\AgentCustomerAccessMode.java`
- Create: `D:\GitCode\customerservice\src\main\java\com\xjjk\cs\agent\domain\AgentCustomerAccessScope.java`
- Create: `D:\GitCode\customerservice\src\main\java\com\xjjk\cs\agent\service\AgentCustomerAccessService.java`
- Test: `D:\GitCode\customerservice\src\test\java\com\xjjk\cs\agent\service\AgentCustomerAccessServiceTest.java`

- [ ] **Step 1: Write failing scope tests**

```java
@RunWith(MockitoJUnitRunner.class)
public class AgentCustomerAccessServiceTest {
    @Mock private FeignCommonService feignCommonService;
    @InjectMocks private AgentCustomerAccessService service;

    @Test
    public void globalPrivilegeProducesGlobalScope() {
        UserPrivilegeDetailDTO privilege = new UserPrivilegeDetailDTO();
        privilege.setIsGlobal(true);
        when(feignCommonService.getUserPrivilege(10567L)).thenReturn(privilege);
        assertEquals(AgentCustomerAccessMode.GLOBAL, service.resolve(10567L).getMode());
    }

    @Test
    public void assignedUsersProduceBoundedScope() {
        UserPrivilegeDetailDTO privilege = new UserPrivilegeDetailDTO();
        privilege.setIsGlobal(false);
        when(feignCommonService.getUserPrivilege(10567L)).thenReturn(privilege);
        when(feignCommonService.getDataPrivilege(10567L))
                .thenReturn(Arrays.asList(10567L, 10568L));
        AgentCustomerAccessScope scope = service.resolve(10567L);
        assertEquals(AgentCustomerAccessMode.ASSIGNED_USER_SET, scope.getMode());
        assertEquals(Arrays.asList(10567L, 10568L), scope.getAllowedUserIds());
    }

    @Test(expected = IllegalStateException.class)
    public void missingPrivilegeFailsClosed() {
        when(feignCommonService.getUserPrivilege(10567L)).thenReturn(null);
        service.resolve(10567L);
    }
}
```

- [ ] **Step 2: Run RED**

Run `mvn -Dtest=AgentCustomerAccessServiceTest test` from `customerservice`.

Expected: compilation fails because the new access classes do not exist.

- [ ] **Step 3: Implement the scope and resolver**

Create enum values `GLOBAL`, `ASSIGNED_USER_SET`, `SELF`. The immutable scope rejects nonpositive user IDs, empty assigned-user sets, and lists attached to `GLOBAL` or `SELF`.

Resolver logic:

```java
UserPrivilegeDetailDTO privilege = feignCommonService.getUserPrivilege(userId);
if (privilege == null || privilege.getIsGlobal() == null) {
    throw new IllegalStateException("客户权限服务响应无效");
}
if (Boolean.TRUE.equals(privilege.getIsGlobal())) {
    return AgentCustomerAccessScope.global(userId);
}
List<Long> allowedUsers = feignCommonService.getDataPrivilege(userId);
if (allowedUsers == null) {
    throw new IllegalStateException("客户权限服务响应无效");
}
List<Long> normalized = Stream.concat(Stream.of(userId), allowedUsers.stream())
        .filter(Objects::nonNull).filter(value -> value > 0).distinct()
        .collect(Collectors.toList());
return normalized.size() == 1
        ? AgentCustomerAccessScope.self(userId)
        : AgentCustomerAccessScope.assigned(userId, normalized);
```

An empty or invalid downstream response never grants global access.

- [ ] **Step 4: Run GREEN**

Run `mvn -Dtest=AgentCustomerAccessServiceTest test` and require zero failures.

- [ ] **Step 5: Commit only Task 1 files**

```powershell
git commit --only -m "feat: model agent customer access scope" -- src/main/java/com/xjjk/cs/agent/domain/AgentCustomerAccessMode.java src/main/java/com/xjjk/cs/agent/domain/AgentCustomerAccessScope.java src/main/java/com/xjjk/cs/agent/service/AgentCustomerAccessService.java src/test/java/com/xjjk/cs/agent/service/AgentCustomerAccessServiceTest.java
```

### Task 2: Add the exact, scoped customer internal endpoint

**Files:**
- Create all `customerservice` Agent API, config, security, DAO and query-service files from the file map.
- Test: `AgentCustomerQueryServiceTest`, `AgentCustomerAuthInterceptorTest`, `AgentCustomerControllerTest`.

- [ ] **Step 1: Write failing query tests**

Cover code-first `AUTO`, exact-name fallback, duplicate customer IDs, limit 10, name masking and invalid inputs. The central behavior is:

```java
@Test
public void autoFallsBackToExactNameAndReturnsMaskedDuplicates() {
    when(accessService.resolve(10567L)).thenReturn(AgentCustomerAccessScope.self(10567L));
    when(mapper.findByCustomerCode(any())).thenReturn(Collections.emptyList());
    when(mapper.findByCustomerName(any())).thenReturn(Arrays.asList(
            row(1L, "C001", "张三"), row(2L, "C002", "张三")));
    AgentCustomerSearchResponse result = service.search(
            request("张三", AgentCustomerMatchType.AUTO), identity(10567L));
    assertEquals(AgentCustomerMatchType.CUSTOMER_NAME, result.getMatchedBy());
    assertEquals("张*", result.getItems().get(0).getDisplayName());
    assertEquals(2, result.getItems().size());
}
```

- [ ] **Step 2: Run RED**

Run `mvn -Dtest=AgentCustomerQueryServiceTest test`; expect missing production classes.

- [ ] **Step 3: Implement exact SQL with mandatory access conditions**

Create separate `findByCustomerCode` and `findByCustomerName` methods. Both use equality, not `LIKE`. Their common SQL shape is:

```java
@Select({
    "<script>",
    "SELECT c.Id customerId,c.Code customerCode,c.Name customerName,",
    "grade.Name gradeName,asset.Name assetTypeName,c.Type customerType",
    "FROM customer c",
    "LEFT JOIN md.sys_dic_item grade ON grade.Id=c.GradeId AND grade.IsDeleted=0",
    "LEFT JOIN md.sys_dic_item asset ON asset.Id=c.AssetsType AND asset.IsDeleted=0",
    "<if test=\"param.accessMode != 'GLOBAL'\">",
    "INNER JOIN cus_belong b ON b.CustomerId=c.Id AND b.IsDeleted=0",
    "</if>",
    "WHERE c.IsDeleted=0 AND c.Code=#{param.keyword}",
    "<choose>",
    "<when test=\"param.accessMode == 'GLOBAL'\"></when>",
    "<when test=\"param.accessMode == 'SELF'\">AND b.CurrentUserId=#{param.userId}</when>",
    "<when test=\"param.accessMode == 'ASSIGNED_USER_SET' and param.allowedUserIds.size() > 0\">",
    "AND b.CurrentUserId IN",
    "<foreach collection=\"param.allowedUserIds\" item=\"id\" open=\"(\" separator=\",\" close=\")\">#{id}</foreach>",
    "</when>",
    "<otherwise>AND 1=0</otherwise>",
    "</choose>",
    "ORDER BY c.Id DESC LIMIT #{param.limit}",
    "</script>"
})
List<AgentCustomerRow> findByCustomerCode(@Param("param") AgentCustomerQueryParam param);
```

Use limit 11 to determine truncation, deduplicate by customer ID, and return at most 10.

- [ ] **Step 4: Implement the response whitelist**

`AgentCustomerItemResponse` contains exactly:

```java
private Long customerId;
private String customerCode;
private String displayName;
private String gradeName;
private String assetTypeName;
private String customerTypeName;
```

Mask a multi-code-point name by keeping the first code point and replacing the remainder with `*`. Read grade and asset-type labels from `md.sys_dic_item`; return `未知` when the corresponding active dictionary row is absent. Map customer type `1` to `普通客户`, `2` to `分销商`, and every other value to `未知`.

- [ ] **Step 5: Write failing internal-auth tests**

Test missing/wrong Token, missing or nonpositive tenant-user-org headers, blank request ID, and successful trusted identity attachment. Run `mvn -Dtest=AgentCustomerAuthInterceptorTest test` and observe RED.

- [ ] **Step 6: Implement authentication and controller**

Compare Token bytes with `MessageDigest.isEqual`. Register the interceptor only for `/internal/agent/customers/**`. Enable all internal components only when `agent.customer.internal-api.enabled=true`.

Controller:

```java
@PostMapping("/search")
public ReturnMsg<AgentCustomerSearchResponse> search(
        HttpServletRequest servletRequest,
        @Valid @RequestBody AgentCustomerSearchRequest request) {
    AgentCustomerRequestIdentity identity = AgentCustomerRequestIdentity.fromRequest(servletRequest);
    return ReturnUtil.success(queryService.search(request, identity));
}
```

Validate a nonblank internal Token and query limit from 1 through 100.

- [ ] **Step 7: Run focused and full tests**

```powershell
mvn -Dtest=AgentCustomerAccessServiceTest,AgentCustomerQueryServiceTest,AgentCustomerAuthInterceptorTest,AgentCustomerControllerTest test
mvn test
```

- [ ] **Step 8: Commit Task 2 exact paths**

Commit only the new `src/main/java/com/xjjk/cs/agent/**` and `src/test/java/com/xjjk/cs/agent/**` files with message `feat: expose guarded agent customer search`.

### Task 3: Query rich orders by trusted customer ID

**Files:**
- Create `D:\GitCode\order\src\main\java\com\xjjk\ec\oms\agent\api\AgentCustomerOrderRequest.java`.
- Modify `D:\GitCode\order\src\main\java\com\xjjk\ec\oms\agent\api\AgentOrderIdentifierType.java`.
- Modify the four existing order Agent classes identified in the file map and their tests.

- [ ] **Step 1: Write failing service tests**

```java
@Test
void searchesRecentAuthorizedOrdersForTrustedCustomer() {
    when(accessService.resolve(identity)).thenReturn(AgentOrderAccessScope.self(10567L));
    when(mapper.findByCustomerId(any())).thenReturn(List.of(orderRow(20L), orderRow(19L)));
    stubRichRowsFor(20L, 19L);
    AgentOrderSearchResponse response = service.searchByCustomerId(80001L, identity);
    assertThat(response.getItems()).hasSize(2);
    verify(mapper).findByCustomerId(argThat(param ->
            param.getCustomerId().equals(80001L)
                    && param.getAccessMode().equals("SELF")));
}
```

- [ ] **Step 2: Run RED**

Run `mvn -Dtest=AgentOrderQueryServiceTest test`; expect missing method failures.

- [ ] **Step 3: Add customer ID plus access-scope SQL**

Use a dedicated parameter factory. SQL must include:

```sql
WHERE o.IsDeleted = 0
  AND o.CustomerId = #{param.customerId}
  AND <GLOBAL | o.UserOrgId IN (...) | o.UserId = #{param.userId}>
ORDER BY o.Id DESC
LIMIT 6
```

The sixth row determines truncation; return at most five cards.

Add `CUSTOMER` to `AgentOrderIdentifierType` and use it only as the response query source. Existing identifier dispatch must never attempt a SQL lookup for `CUSTOMER`.

- [ ] **Step 4: Reuse one rich aggregation method**

Extract the existing money, goods, shipment and recipient batch assembly into:

```java
private AgentOrderSearchResponse assembleResponse(
        AgentOrderIdentifierType matchedBy,
        List<AgentOrderRow> rawRows) {
    // Existing deduplication, five-card cap, six batch reads and card assembler move here.
}
```

Both identifier search and customer search invoke this method. No aggregation SQL or card mapping is duplicated.

- [ ] **Step 5: Add controller test, observe RED, then add endpoint**

```java
@PostMapping("/by-customer")
public ReturnMsg<AgentOrderSearchResponse> byCustomer(
        HttpServletRequest servletRequest,
        @Valid @RequestBody AgentCustomerOrderRequest request) {
    AgentRequestIdentity identity = AgentRequestIdentityResolver.fromRequestAttribute(servletRequest);
    return ReturnUtil.success(orderQueryService.searchByCustomerId(request.getCustomerId(), identity));
}
```

`customerId` must be positive. The same existing internal interceptor protects the route.

- [ ] **Step 6: Run focused and full tests**

```powershell
mvn -Dtest=AgentOrderControllerTest,AgentOrderQueryMapperTest,AgentOrderQueryServiceTest test
mvn test
```

- [ ] **Step 7: Commit only Task 3 paths**

Use message `feat: query authorized orders by customer`; preserve existing modified Feign files and unrelated tests.

### Task 4: Add the Agent customer gateway and `search_customers`

**Files:**
- Create `D:\GitCode\order-logistics-agent-server\src\main\java\com\xjjk\agent\customer\domain\*`.
- Create matching `service`, `client`, `config` and `tool` packages.
- Add matching focused tests.

- [ ] **Step 1: Write failing gateway tests**

Prove trusted headers originate from `AgentIdentity`:

```java
verify(client).search(
        eq(internalToken), eq(1L), eq(10567L), eq(23L), eq(requestId),
        eq(new CustomerSearchClient.SearchRequest("C001", CustomerMatchType.CUSTOMER_CODE)));
```

Test response bounds and required fields. Test one retry only for 502/503/504 and explicit connect/read timeouts; never retry 400/401/403/404, DNS, TLS or malformed responses.

- [ ] **Step 2: Run RED**

Run `./mvnw.cmd -Dtest=CustomerServiceGatewayTest test`.

- [ ] **Step 3: Implement Feign and gateway boundary**

```java
@FeignClient(name = "agent-customer-search",
    url = "${integration.customer.base-url}",
    configuration = CustomerFeignConfiguration.class)
interface CustomerSearchClient {
    @PostMapping("/internal/agent/customers/search")
    CustomerServiceResponse<SearchData> search(
        @RequestHeader("X-Agent-Internal-Token") String token,
        @RequestHeader("X-Agent-Tenant-Id") long tenantId,
        @RequestHeader("X-Agent-User-Id") long userId,
        @RequestHeader("X-Agent-Org-Id") long orgId,
        @RequestHeader("X-Agent-Request-Id") String requestId,
        @RequestBody SearchRequest request);
}
```

Reject over 10 items, inconsistent total/truncated values, blank customer codes, unsafe names, nonpositive customer IDs and null required fields. Use a dedicated `customerSearch` circuit breaker.

- [ ] **Step 4: Write failing tool tests**

```java
assertThat(ui.toolName()).isEqualTo("search_customers");
assertThat(ui.kind()).isEqualTo("customer-list");
assertThat(ui.schemaVersion()).isEqualTo(1);
assertThat(modelText).contains("C001").contains("张*");
assertThat(modelText).doesNotContain("customerId").doesNotContain("80001");
```

- [ ] **Step 5: Implement `search_customers`**

```java
@Tool(name = "search_customers",
      description = "按完整客户编号或完整客户姓名查询当前坐席有权访问的客户。姓名重名时返回列表供用户确认。")
public String searchCustomers(
        @ToolParam(description = "完整客户编号或完整客户姓名") String keyword,
        @ToolParam(description = "AUTO、CUSTOMER_CODE、CUSTOMER_NAME；不确定时用AUTO", required = false)
        String matchType,
        ToolContext toolContext)
```

Publish `ToolUiResult("search_customers", "customer-list", 1, queriedAt, result)` and return bounded safe model text.

- [ ] **Step 6: Implement independent availability**

Use `agent.tool.customer` with fail-closed `OFF|ALLOWLIST|ALL`. Missing identity, false enabled flag, blank or unknown mode returns unavailable.

- [ ] **Step 7: Run tests and commit**

```powershell
./mvnw.cmd -Dtest=CustomerServiceGatewayTest,CustomerQueryToolsTest,CustomerToolAvailabilityTest test
```

Commit only the customer package and matching tests with `feat: add guarded customer search tool`.

### Task 5: Add `list_customer_orders` without exposing customer ID

**Files:**
- Modify Agent order client, gateway, tool, availability and model registration.
- Modify Agent `OrderIdentifierType` so the validated downstream response can represent `CUSTOMER`.
- Extend their focused tests.

- [ ] **Step 1: Write failing orchestration tests**

```java
when(customerGateway.search("C001", CUSTOMER_CODE, identity, requestId))
        .thenReturn(singleCustomer(80001L, "C001"));
when(orderGateway.searchByCustomerId(80001L, identity, requestId))
        .thenReturn(orderResult());
String answer = tools.listCustomerOrders("C001", toolContext);
verify(orderGateway).searchByCustomerId(80001L, identity, requestId);
assertThat(answer).doesNotContain("80001");
```

Zero or multiple customer matches must stop before the order client.

- [ ] **Step 2: Run RED**

Run `./mvnw.cmd -Dtest=OrderQueryToolsTest,OrderServiceGatewayTest test`.

- [ ] **Step 3: Extend the order internal client and gateway**

Add `byCustomer(CustomerOrderRequest customerId)` to the Feign contract and `searchByCustomerId(long, AgentIdentity, String)` to `OrderQueryGateway`. Reuse the existing search circuit breaker, retry classifier and rich response mapper.

- [ ] **Step 4: Implement the tool through one application service**

```java
@Tool(name = "list_customer_orders",
      description = "按完整客户编号查询该客户最近的可见订单。必须重新确认客户权限和订单权限。")
public String listCustomerOrders(
        @ToolParam(description = "完整客户编号") String customerCode,
        ToolContext toolContext)
```

Resolve the customer code through the customer gateway, require one result, extract internal ID, then query order. Publish existing `order-list` schema 1. Customer ID never enters model text or logs.

- [ ] **Step 5: Register tools per request**

Add independent `agent.tool.customer-order` rollout. In `AiChatService`, add customer and customer-order callbacks only when each capability is available. Disabled tool schemas must be absent from the model request.

- [ ] **Step 6: Run tests and commit**

```powershell
./mvnw.cmd -Dtest=OrderServiceGatewayTest,OrderQueryToolsTest,OrderToolAvailabilityTest,AiChatServiceTest test
```

Commit only the planned files with `feat: query customer orders through trusted resolution`.

### Task 6: Persist customer cards and dispatch the deterministic action

**Files:**
- Modify Agent action request/type/dispatcher and result-history whitelist.
- Extend focused action, history and SSE contract tests.

- [ ] **Step 1: Write failing schema/history tests**

Prove `customer-list` schema 1 restores and unknown versions are skipped. Prove the SSE payload includes safe card fields and excludes prohibited fields.

- [ ] **Step 2: Run RED**

```powershell
./mvnw.cmd -Dtest=AgentMessageResultQueryServiceTest,ChatStreamBusinessResultContractTest test
```

- [ ] **Step 3: Add the result schema**

```java
private static final Map<String, Set<Integer>> SUPPORTED_RESULT_SCHEMAS = Map.of(
    "product-list", Set.of(1),
    "order-list", Set.of(1),
    "logistics-timeline", Set.of(1),
    "customer-list", Set.of(1));
```

No database migration is needed because the existing result table is generic.

- [ ] **Step 4: Write failing action tests**

Test `QUERY_CUSTOMER_ORDERS`, customer-code validation, unavailable rollout and proof that a changed code is still reauthorized before order lookup.

- [ ] **Step 5: Implement the action**

Add optional `customerCode` to `ChatActionRequest` and dispatch:

```java
case QUERY_CUSTOMER_ORDERS -> customerOrderApplicationService.query(
        action.customerCode(), identity, requestId);
```

Both the tool and dispatcher use this same application service. Return `order-list` and fixed assistant text.

- [ ] **Step 6: Run tests and commit**

```powershell
./mvnw.cmd -Dtest=ChatActionDispatcherTest,AgentMessageResultQueryServiceTest,ChatStreamBusinessResultContractTest test
```

Commit exact planned paths with `feat: restore customer cards and dispatch customer orders`.

### Task 7: Render the customer card

**Files:**
- Create `D:\GitCode\order-logistics-agent-web\src\renderer\src\components\CustomerListCard.vue`.
- Create its test.
- Modify chat contract, accumulator and `ChatWindow` with focused tests.

- [ ] **Step 1: Write failing contract/accumulator tests**

Expected contract:

```ts
export interface CustomerListResult {
  matchedBy: 'CUSTOMER_CODE' | 'CUSTOMER_NAME'
  total: number
  truncated: boolean
  queriedAt: string
  items: Array<{
    customerId: number
    customerCode: string
    displayName: string
    gradeName: string
    assetTypeName: string
    customerTypeName: string
  }>
}
```

Reject missing fields, invalid totals and over 10 items. Internal `customerId` may remain in transport state but is never rendered.

- [ ] **Step 2: Run RED**

Run `npm test -- src/renderer/src/stores/chat-accumulator.test.ts`.

- [ ] **Step 3: Implement strict parsing**

Add `customer-list` schema 1 to the discriminated result union. Skip corrupt historical results and use the existing safe live-result error path.

- [ ] **Step 4: Write failing card tests**

Test masked name/code/labels, no empty pills, no rendered internal ID, up to ten cards, and exactly one emitted action:

```ts
expect(wrapper.emitted('action')?.[0]).toEqual([
  { type: 'QUERY_CUSTOMER_ORDERS', customerCode: 'C001' }
])
```

- [ ] **Step 5: Implement and wire the card**

Follow existing order-card spacing, focus styling and green action button. Render for `kind === 'customer-list'` and send actions through the existing authenticated action transport. Preserve both user and assistant copy controls.

- [ ] **Step 6: Verify and commit**

```powershell
npm test -- src/renderer/src/components/CustomerListCard.test.ts src/renderer/src/stores/chat-accumulator.test.ts src/renderer/src/components/ChatWindow.test.ts
npm run typecheck
npm run build
```

Commit Task 7 exact paths with `feat: display customer search cards`.

### Task 8: Produce Nacos handoff and run full verification

**Files:**
- Create `D:\GitCode\order-logistics-agent-server\docs\runbook\customer-order-tool-verification.md`.
- Do not modify repository property or YAML files.

- [ ] **Step 1: Add the exact disabled-by-default Nacos block**

```properties
# customerservice
agent.customer.internal-api.enabled=false
agent.customer.internal-api.internal-token=${AGENT_INTERNAL_TOKEN}
agent.customer.internal-api.query-limit=11

# Agent -> customerservice
integration.customer.base-url=http://127.0.0.1:${CUSTOMER_SERVICE_PORT}
integration.customer.internal-token=${AGENT_INTERNAL_TOKEN}
spring.cloud.openfeign.client.config.agent-customer-search.connect-timeout=1000
spring.cloud.openfeign.client.config.agent-customer-search.read-timeout=3000
spring.cloud.openfeign.client.config.agent-customer-search.logger-level=basic

agent.tool.customer.enabled=false
agent.tool.customer.rollout-mode=OFF
agent.tool.customer.allowed-org-ids=
agent.tool.customer-order.enabled=false
agent.tool.customer-order.rollout-mode=OFF
agent.tool.customer-order.allowed-org-ids=
```

Add the exact Resilience4j keys used in code, rollout order and rollback steps. The real Token remains an environment variable.

- [ ] **Step 2: Run all backend tests freshly**

```powershell
# customerservice
mvn test
# order
mvn test
# Agent
./mvnw.cmd test
```

- [ ] **Step 3: Run all frontend checks freshly**

```powershell
npm test
npm run typecheck
npm run build
```

- [ ] **Step 4: Audit repository boundaries**

Run `git status --short` and `git log -8 --oneline` in all four repositories. Confirm only planned files were committed and pre-existing unrelated changes remain.

- [ ] **Step 5: Commit only the runbook**

```powershell
git commit --only -m "docs: add customer order tool verification" -- docs/runbook/customer-order-tool-verification.md
```

- [ ] **Step 6: Manual acceptance after the user applies Nacos values**

Verify exact-code customer card, duplicate-name selection, unauthorized/missing indistinguishability, natural-language customer-order lookup, deterministic button lookup, order-to-logistics action, history restoration, and absence of Token/full phone/address/ID card/birthday/internal customer ID from logs and natural-language output.
