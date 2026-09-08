# After-Sale Query Tools Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为坐席 Agent 增加受权限约束的售后单搜索、售后详情查询，以及可恢复的列表/详情卡片展示。

**Architecture:** `aftersale` 提供独立的 Agent 内部只读接口，在服务边界完成 Token 校验、可信身份解析、组织范围限制、脱敏和有界 DTO 映射；Agent Server 通过 Feign、显式重试和独立熔断器调用该接口，并把最小事实交给模型、完整结果交给 SSE；桌面端渲染列表和详情卡片，列表“查看详情”使用白名单动作直接调用同一详情应用服务。

**Tech Stack:** Java 21、Spring Boot、Spring MVC、MyBatis-Plus、OpenFeign、Spring Cloud Circuit Breaker/Resilience4j、Spring AI Tools、JUnit 5、Mockito、Vue 3、Pinia、TypeScript、Vitest、Electron IPC、Nacos。

---

## 工作区与提交边界

- 售后仓库：`D:\GitCode\aftersale`
- Agent Server：`D:\GitCode\order-logistics-agent-server`
- 桌面端：`D:\GitCode\order-logistics-agent-web`
- 保留三个仓库里所有已有未提交修改；每次 `git add` 只列出本任务文件。
- 配置只写入 Nacos。代码不得新增或修改 `application.properties`。
- 售后列表最多 5 条；详情的原单商品与换货商品分别最多 20 条。
- 所有金额都是整数分，Java/JSON 字段统一以 `InFen` 结尾。

### Task 1: 建立 aftersale 内部接口的配置、认证和可信身份边界

**Files:**
- Create: `D:\GitCode\aftersale\AfterSale\src\main\java\com\xjjk\ec\AfterSale\agent\config\AgentAfterSaleProperties.java`
- Create: `D:\GitCode\aftersale\AfterSale\src\main\java\com\xjjk\ec\AfterSale\agent\security\AgentAfterSaleIdentity.java`
- Create: `D:\GitCode\aftersale\AfterSale\src\main\java\com\xjjk\ec\AfterSale\agent\security\AgentAfterSaleAuthInterceptor.java`
- Create: `D:\GitCode\aftersale\AfterSale\src\main\java\com\xjjk\ec\AfterSale\agent\config\AgentAfterSaleWebMvcConfiguration.java`
- Test: `D:\GitCode\aftersale\AfterSale\src\test\java\com\xjjk\ec\AfterSale\agent\security\AgentAfterSaleAuthInterceptorTest.java`

- [ ] **Step 1: 写认证失败和可信身份成功的测试**

```java
@ExtendWith(MockitoExtension.class)
class AgentAfterSaleAuthInterceptorTest {
    @Mock HttpServletRequest request;
    @Mock HttpServletResponse response;

    @Test
    void rejectsWrongToken() throws Exception {
        AgentAfterSaleAuthInterceptor interceptor = interceptor("expected-token");
        when(request.getHeader("X-Agent-Internal-Token")).thenReturn("wrong-token");

        assertFalse(interceptor.preHandle(request, response, new Object()));
        verify(response).sendError(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    void acceptsTrustedHeadersAndStoresIdentity() throws Exception {
        AgentAfterSaleAuthInterceptor interceptor = interceptor("expected-token");
        when(request.getHeader("X-Agent-Internal-Token")).thenReturn("expected-token");
        when(request.getHeader("X-Agent-Tenant-Id")).thenReturn("1");
        when(request.getHeader("X-Agent-User-Id")).thenReturn("10567");
        when(request.getHeader("X-Agent-Org-Id")).thenReturn("20");
        when(request.getHeader("X-Agent-Request-Id"))
                .thenReturn("b8e3115a-235c-431e-8f1a-147bb54fa852");

        assertTrue(interceptor.preHandle(request, response, new Object()));
        verify(request).setAttribute(
                AgentAfterSaleIdentity.REQUEST_ATTRIBUTE,
                new AgentAfterSaleIdentity(1L, 10567L, 20L,
                        "b8e3115a-235c-431e-8f1a-147bb54fa852"));
    }

    private AgentAfterSaleAuthInterceptor interceptor(String token) {
        AgentAfterSaleProperties properties = new AgentAfterSaleProperties();
        properties.setEnabled(true);
        properties.setToken(token);
        properties.setSupportedTenantId(1L);
        return new AgentAfterSaleAuthInterceptor(properties);
    }
}
```

- [ ] **Step 2: 运行测试并确认先失败**

Run: `mvn -pl AfterSale -Dtest=AgentAfterSaleAuthInterceptorTest test`

Expected: FAIL，编译器提示 `AgentAfterSaleAuthInterceptor` 等类型不存在。

- [ ] **Step 3: 实现配置、身份对象和拦截器**

```java
@Data
@Validated
@ConfigurationProperties(prefix = "agent.aftersale.internal-api")
public class AgentAfterSaleProperties {
    private boolean enabled;
    @NotBlank private String token;
    @Positive private long supportedTenantId;
    @Min(1) @Max(5) private int queryLimit = 5;
    @Min(1) @Max(20) private int detailItemLimit = 20;
}
```

```java
public record AgentAfterSaleIdentity(
        long tenantId, long userId, long orgId, String requestId) {
    public static final String REQUEST_ATTRIBUTE =
            AgentAfterSaleIdentity.class.getName();
}
```

```java
@RequiredArgsConstructor
public class AgentAfterSaleAuthInterceptor implements HandlerInterceptor {
    private final AgentAfterSaleProperties properties;

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler) throws IOException {
        if (!properties.isEnabled()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return false;
        }
        String actual = request.getHeader("X-Agent-Internal-Token");
        if (!constantTimeEquals(properties.getToken(), actual)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        try {
            long tenantId = positiveLong(request, "X-Agent-Tenant-Id");
            long userId = positiveLong(request, "X-Agent-User-Id");
            long orgId = positiveLong(request, "X-Agent-Org-Id");
            String requestId = UUID.fromString(
                    request.getHeader("X-Agent-Request-Id")).toString();
            if (tenantId != properties.getSupportedTenantId()) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN);
                return false;
            }
            request.setAttribute(AgentAfterSaleIdentity.REQUEST_ATTRIBUTE,
                    new AgentAfterSaleIdentity(tenantId, userId, orgId, requestId));
            return true;
        } catch (RuntimeException exception) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return false;
        }
    }

    private long positiveLong(HttpServletRequest request, String name) {
        long value = Long.parseLong(request.getHeader(name));
        if (value <= 0) throw new IllegalArgumentException(name);
        return value;
    }

    private boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) return false;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
```

```java
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AgentAfterSaleProperties.class)
@RequiredArgsConstructor
public class AgentAfterSaleWebMvcConfiguration implements WebMvcConfigurer {
    private final AgentAfterSaleProperties properties;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AgentAfterSaleAuthInterceptor(properties))
                .addPathPatterns("/internal/agent/after-sales/**");
    }
}
```

- [ ] **Step 4: 运行测试并确认通过**

Run: `mvn -pl AfterSale -Dtest=AgentAfterSaleAuthInterceptorTest test`

Expected: PASS，2 tests run，0 failures。

- [ ] **Step 5: 提交认证边界**

```powershell
git add AfterSale/src/main/java/com/xjjk/ec/AfterSale/agent/config/AgentAfterSaleProperties.java AfterSale/src/main/java/com/xjjk/ec/AfterSale/agent/security/AgentAfterSaleIdentity.java AfterSale/src/main/java/com/xjjk/ec/AfterSale/agent/security/AgentAfterSaleAuthInterceptor.java AfterSale/src/main/java/com/xjjk/ec/AfterSale/agent/config/AgentAfterSaleWebMvcConfiguration.java AfterSale/src/test/java/com/xjjk/ec/AfterSale/agent/security/AgentAfterSaleAuthInterceptorTest.java
git commit -m "feat: add agent after-sale authentication boundary"
```

### Task 2: 实现 aftersale 有界搜索查询

**Files:**
- Create: `D:\GitCode\aftersale\AfterSale\src\main\java\com\xjjk\ec\AfterSale\agent\api\AgentAfterSaleSearchRequest.java`
- Create: `D:\GitCode\aftersale\AfterSale\src\main\java\com\xjjk\ec\AfterSale\agent\api\AgentAfterSaleSearchResponse.java`
- Create: `D:\GitCode\aftersale\AfterSale\src\main\java\com\xjjk\ec\AfterSale\agent\domain\AgentAfterSaleIdentifierType.java`
- Create: `D:\GitCode\aftersale\AfterSale\src\main\java\com\xjjk\ec\AfterSale\agent\dao\AgentAfterSaleMapper.java`
- Create: `D:\GitCode\aftersale\AfterSale\src\main\java\com\xjjk\ec\AfterSale\agent\service\AgentAfterSaleAccessService.java`
- Create: `D:\GitCode\aftersale\AfterSale\src\main\java\com\xjjk\ec\AfterSale\agent\service\AgentAfterSaleQueryService.java`
- Test: `D:\GitCode\aftersale\AfterSale\src\test\java\com\xjjk\ec\AfterSale\agent\service\AgentAfterSaleQueryServiceTest.java`

- [ ] **Step 1: 写搜索规则、组织范围和截断测试**

```java
@ExtendWith(MockitoExtension.class)
class AgentAfterSaleQueryServiceTest {
    @Mock AgentAfterSaleMapper mapper;
    @Mock AgentAfterSaleAccessService accessService;
    AgentAfterSaleQueryService service;

    @BeforeEach
    void setUp() {
        service = new AgentAfterSaleQueryService(mapper, accessService, 5);
    }

    @Test
    void searchesCustomerCodeInsideTrustedOrgScopeAndTruncates() {
        AgentAfterSaleIdentity identity = new AgentAfterSaleIdentity(1, 10567, 20, UUID.randomUUID().toString());
        when(accessService.resolveOrgIds(identity)).thenReturn(List.of(20L, 21L));
        when(mapper.count(any())).thenReturn(6L);
        when(mapper.search(any(), eq(5))).thenReturn(List.of(
                row("AS6"), row("AS5"), row("AS4"), row("AS3"), row("AS2")));

        AgentAfterSaleSearchResponse result = service.search(
                new AgentAfterSaleSearchRequest("CUSTOMER_CODE", "C24101816040001", null, null), identity);

        assertEquals(6, result.total());
        assertTrue(result.truncated());
        assertEquals(5, result.items().size());
        ArgumentCaptor<AgentAfterSaleMapper.SearchCriteria> criteria =
                ArgumentCaptor.forClass(AgentAfterSaleMapper.SearchCriteria.class);
        verify(mapper).count(criteria.capture());
        assertEquals(List.of(20L, 21L), criteria.getValue().orgIds());
        assertEquals(AgentAfterSaleIdentifierType.CUSTOMER_CODE, criteria.getValue().identifierType());
    }

    @Test
    void rejectsOneCharacterCustomerName() {
        assertThrows(IllegalArgumentException.class, () -> service.search(
                new AgentAfterSaleSearchRequest("CUSTOMER_NAME", "张", null, null),
                new AgentAfterSaleIdentity(1, 1, 20, UUID.randomUUID().toString())));
        verifyNoInteractions(mapper, accessService);
    }
}
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `mvn -pl AfterSale -Dtest=AgentAfterSaleQueryServiceTest test`

Expected: FAIL，搜索契约和服务尚不存在。

- [ ] **Step 3: 定义明确的搜索契约**

```java
public enum AgentAfterSaleIdentifierType {
    AFTER_SALE_CODE, ORDER_CODE, CUSTOMER_CODE, CUSTOMER_NAME;

    public static AgentAfterSaleIdentifierType parse(String value) {
        if (value == null) throw new IllegalArgumentException("identifierType 不能为空");
        return valueOf(value.strip().toUpperCase(Locale.ROOT));
    }
}
```

```java
public record AgentAfterSaleSearchRequest(
        String identifierType,
        String identifier,
        OffsetDateTime startTime,
        OffsetDateTime endTime) {}
```

```java
public record AgentAfterSaleSearchResponse(
        String matchedBy,
        long total,
        boolean truncated,
        OffsetDateTime queriedAt,
        List<Item> items) {
    public record Item(
            String afterSaleCode,
            Integer statusCode,
            String statusText,
            OffsetDateTime createdAt,
            boolean finished,
            OffsetDateTime finishedAt,
            String customerDisplayName,
            String customerCode,
            String orderCode,
            String returnLogisticsCode,
            String assigneeDisplayName) {}
}
```

- [ ] **Step 4: 新增专用 Mapper 条件和查询 SQL**

```java
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface AgentAfterSaleMapper {
    record SearchCriteria(
            AgentAfterSaleIdentifierType identifierType,
            String identifier,
            LocalDateTime startTime,
            LocalDateTime endTime,
            List<Long> orgIds) {}

    record SearchRow(
            String afterSaleCode,
            Integer statusCode,
            Date createdAt,
            Integer finished,
            Date finishedAt,
            String customerName,
            String customerCode,
            String orderCode,
            String returnLogisticsCode,
            String assigneeName) {}

    @Select(COUNT_SQL)
    long count(@Param("criteria") SearchCriteria criteria);

    @Select(LIST_SQL)
    List<SearchRow> search(@Param("criteria") SearchCriteria criteria, @Param("limit") int limit);

    String WHERE_SQL = " from after_sale a where a.IsDeleted=0 and a.MakeUserOrgId in " +
            "<foreach collection='criteria.orgIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> " +
            "<choose>" +
            "<when test='criteria.identifierType.toString()==\"AFTER_SALE_CODE\"'>and a.Code like concat(#{criteria.identifier},'%')</when>" +
            "<when test='criteria.identifierType.toString()==\"ORDER_CODE\"'>and a.OrderCode=#{criteria.identifier}</when>" +
            "<when test='criteria.identifierType.toString()==\"CUSTOMER_CODE\"'>and a.CustomerCode=#{criteria.identifier}</when>" +
            "<otherwise>and a.CustomerName like concat('%',#{criteria.identifier},'%')</otherwise>" +
            "</choose>" +
            "<if test='criteria.startTime!=null'>and a.CreateTime &gt;= #{criteria.startTime}</if>" +
            "<if test='criteria.endTime!=null'>and a.CreateTime &lt;= #{criteria.endTime}</if>";
    String COUNT_SQL = "<script>select count(*)" + WHERE_SQL + "</script>";
    String LIST_SQL = "<script>select a.Code afterSaleCode,a.Status statusCode," +
            "a.CreateTime createdAt,a.IsFinished finished,a.FinishTime finishedAt," +
            "a.CustomerName customerName,a.CustomerCode customerCode,a.OrderCode orderCode," +
            "a.ReturnLogisticsCode returnLogisticsCode,a.UserName assigneeName" +
            WHERE_SQL + " order by a.Id desc limit #{limit}</script>";
}
```

- [ ] **Step 5: 实现可信组织范围、参数校验、脱敏和状态映射**

```java
@Service
@RequiredArgsConstructor
public class AgentAfterSaleAccessService {
    private final UserFeignService userFeignService;

    public List<Long> resolveOrgIds(AgentAfterSaleIdentity identity) {
        List<Long> orgIds = userFeignService.getSubGroups(identity.orgId());
        if (orgIds == null || orgIds.isEmpty()) {
            throw new AccessDeniedException("无法确认售后访问范围");
        }
        List<Long> normalized = orgIds.stream()
                .filter(id -> id != null && id > 0).distinct().toList();
        if (normalized.isEmpty()) throw new AccessDeniedException("无法确认售后访问范围");
        return normalized;
    }
}
```

```java
public AgentAfterSaleSearchResponse search(
        AgentAfterSaleSearchRequest request,
        AgentAfterSaleIdentity identity) {
    AgentAfterSaleIdentifierType type = AgentAfterSaleIdentifierType.parse(request.identifierType());
    String identifier = normalize(request.identifier());
    if (type == AgentAfterSaleIdentifierType.CUSTOMER_NAME && identifier.codePointCount(0, identifier.length()) < 2) {
        throw new IllegalArgumentException("客户姓名至少2个字符");
    }
    if (request.startTime() != null && request.endTime() != null
            && request.startTime().isAfter(request.endTime())) {
        throw new IllegalArgumentException("开始时间不能晚于结束时间");
    }
    AgentAfterSaleMapper.SearchCriteria criteria = new AgentAfterSaleMapper.SearchCriteria(
            type, identifier, local(request.startTime()), local(request.endTime()),
            accessService.resolveOrgIds(identity));
    long total = mapper.count(criteria);
    List<AgentAfterSaleSearchResponse.Item> items = mapper.search(criteria, queryLimit).stream()
            .map(this::toItem).toList();
    return new AgentAfterSaleSearchResponse(
            type.name(), total, total > items.size(), OffsetDateTime.now(), items);
}
```

状态映射固定调用 `AfterSaleStatusConst.getValueByCode(statusCode)`；返回 null 时使用 `状态未知` 并只记录 `requestId/statusCode`，日志不得记录查询值。姓名统一调用一个 `maskName(String)` 私有方法：空值返回 `未提供`，一个 Unicode 字符原样返回，两个及以上字符返回首字符加 `*`。

- [ ] **Step 6: 运行搜索测试**

Run: `mvn -pl AfterSale -Dtest=AgentAfterSaleQueryServiceTest test`

Expected: PASS，搜索结果真实总数为 6、返回 5 条并标记截断。

- [ ] **Step 7: 提交搜索能力**

```powershell
git add AfterSale/src/main/java/com/xjjk/ec/AfterSale/agent/api/AgentAfterSaleSearchRequest.java AfterSale/src/main/java/com/xjjk/ec/AfterSale/agent/api/AgentAfterSaleSearchResponse.java AfterSale/src/main/java/com/xjjk/ec/AfterSale/agent/domain/AgentAfterSaleIdentifierType.java AfterSale/src/main/java/com/xjjk/ec/AfterSale/agent/dao/AgentAfterSaleMapper.java AfterSale/src/main/java/com/xjjk/ec/AfterSale/agent/service/AgentAfterSaleAccessService.java AfterSale/src/main/java/com/xjjk/ec/AfterSale/agent/service/AgentAfterSaleQueryService.java AfterSale/src/test/java/com/xjjk/ec/AfterSale/agent/service/AgentAfterSaleQueryServiceTest.java
git commit -m "feat: add bounded agent after-sale search"
```

### Task 3: 实现 aftersale 权限内详情与安全金额映射

**Files:**
- Create: `D:\GitCode\aftersale\AfterSale\src\main\java\com\xjjk\ec\AfterSale\agent\api\AgentAfterSaleDetailRequest.java`
- Create: `D:\GitCode\aftersale\AfterSale\src\main\java\com\xjjk\ec\AfterSale\agent\api\AgentAfterSaleDetailResponse.java`
- Modify: `D:\GitCode\aftersale\AfterSale\src\main\java\com\xjjk\ec\AfterSale\agent\dao\AgentAfterSaleMapper.java`
- Create: `D:\GitCode\aftersale\AfterSale\src\main\java\com\xjjk\ec\AfterSale\agent\service\AgentAfterSaleDetailService.java`
- Test: `D:\GitCode\aftersale\AfterSale\src\test\java\com\xjjk\ec\AfterSale\agent\service\AgentAfterSaleDetailServiceTest.java`

- [ ] **Step 1: 写详情必须先按工单号与组织范围解析的测试**

```java
@Test
void resolvesCodeInsideScopeAndBoundsBothGoodsLists() {
    when(accessService.resolveOrgIds(identity)).thenReturn(List.of(20L, 21L));
    when(mapper.findScopedHeader("AS202609080001", List.of(20L, 21L))).thenReturn(header(88L));
    when(mapper.findItems(88L, 21)).thenReturn(itemRows(21));
    when(mapper.findExchangeGoods(88L, 21)).thenReturn(exchangeRows(21));
    when(mapper.findRefund(88L)).thenReturn(refund(1200L, 300L, 500L));

    AgentAfterSaleDetailResponse result = service.detail(
            new AgentAfterSaleDetailRequest("AS202609080001"), identity);

    assertEquals(20, result.items().size());
    assertTrue(result.itemsTruncated());
    assertEquals(20, result.exchangeGoods().size());
    assertTrue(result.exchangeGoodsTruncated());
    assertEquals(1200L, result.refundSummary().totalCashInFen());
    verify(mapper).findScopedHeader("AS202609080001", List.of(20L, 21L));
}
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `mvn -pl AfterSale -Dtest=AgentAfterSaleDetailServiceTest test`

Expected: FAIL，详情服务和响应契约尚不存在。

- [ ] **Step 3: 定义详情响应，不包含任何内部主键和敏感字段**

```java
public record AgentAfterSaleDetailRequest(String afterSaleCode) {}

public record AgentAfterSaleDetailResponse(
        String afterSaleCode,
        Integer statusCode,
        String statusText,
        OffsetDateTime createdAt,
        boolean finished,
        OffsetDateTime finishedAt,
        String customerDisplayName,
        String customerCode,
        String orderCode,
        String exchangeOrderCode,
        String returnLogisticsCode,
        String returnRemark,
        List<Item> items,
        List<ExchangeGoods> exchangeGoods,
        RefundSummary refundSummary,
        boolean itemsTruncated,
        boolean exchangeGoodsTruncated,
        OffsetDateTime queriedAt) {
    public record Item(
            String goodsName, String skuCode, String specification,
            String reason, Integer originalQuantity, Integer receivedQuantity,
            Integer refundQuantity, Integer exchangeQuantity, Integer sentBackQuantity) {}
    public record ExchangeGoods(
            String goodsName, String skuCode, Integer quantity,
            Long unitPriceInFen, Long subtotalInFen) {}
    public record RefundSummary(
            Long totalCashInFen, Long totalPreStorageInFen,
            Long returnPreStorageInFen, Long returnCouponInFen,
            Long returnIntegral) {}
}
```

- [ ] **Step 4: 给专用 Mapper 增加权限内 Header、商品和退款查询**

```java
@Select("<script>select a.Id id,a.Code afterSaleCode,a.Status statusCode,a.CreateTime createdAt," +
        "a.IsFinished finished,a.FinishTime finishedAt,a.CustomerName customerName," +
        "a.CustomerCode customerCode,a.OrderCode orderCode,a.ReturnOrderCode exchangeOrderCode," +
        "a.ReturnLogisticsCode returnLogisticsCode,a.ReturnRemark returnRemark " +
        "from after_sale a where a.IsDeleted=0 and a.Code=#{code} and a.MakeUserOrgId in " +
        "<foreach collection='orgIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> limit 1</script>")
DetailHeaderRow findScopedHeader(@Param("code") String code, @Param("orgIds") List<Long> orgIds);

@Select("select GoodsName goodsName,SkuCode skuCode,Specification specification," +
        "Reason reason,ReasonRemark reasonRemark,OldAmount originalQuantity," +
        "ReceiveAmount receivedQuantity,ReturnMoneyAmount refundQuantity," +
        "ChangeAmount exchangeQuantity,SentBackAmount sentBackQuantity " +
        "from after_sale_item where IsDeleted=0 and AfterSaleId=#{afterSaleId} order by Id limit #{limit}")
List<ItemRow> findItems(@Param("afterSaleId") long afterSaleId, @Param("limit") int limit);

@Select("select GoodsName goodsName,SkuCode skuCode,Quantity quantity," +
        "SharePrice unitPriceInFen,TotalMoney subtotalInFen " +
        "from after_sale_return_goods where IsDeleted=0 and AfterSaleId=#{afterSaleId} order by Id limit #{limit}")
List<ExchangeGoodsRow> findExchangeGoods(@Param("afterSaleId") long afterSaleId, @Param("limit") int limit);

@Select("select TotalCash totalCashInFen,TotalPreStorage totalPreStorageInFen," +
        "ReturnPreStorage returnPreStorageInFen,ReturnCoupon returnCouponInFen," +
        "ReturnIntegral returnIntegral from after_sale_return_cost " +
        "where IsDeleted=0 and AfterSaleId=#{afterSaleId} limit 1")
RefundRow findRefund(@Param("afterSaleId") long afterSaleId);
```

- [ ] **Step 5: 实现详情服务**

```java
public AgentAfterSaleDetailResponse detail(
        AgentAfterSaleDetailRequest request,
        AgentAfterSaleIdentity identity) {
    String code = normalizeCode(request.afterSaleCode());
    List<Long> orgIds = accessService.resolveOrgIds(identity);
    DetailHeaderRow header = Optional.ofNullable(mapper.findScopedHeader(code, orgIds))
            .orElseThrow(() -> new AgentAfterSaleNotFoundException("售后工单不存在"));
    List<ItemRow> itemRows = mapper.findItems(header.id(), detailItemLimit + 1);
    List<ExchangeGoodsRow> exchangeRows = mapper.findExchangeGoods(header.id(), detailItemLimit + 1);
    RefundRow refund = mapper.findRefund(header.id());
    return new AgentAfterSaleDetailResponse(
            header.afterSaleCode(), header.statusCode(), statusText(header.statusCode()),
            offset(header.createdAt()), Integer.valueOf(1).equals(header.finished()),
            offset(header.finishedAt()), maskName(header.customerName()), header.customerCode(),
            header.orderCode(), header.exchangeOrderCode(), header.returnLogisticsCode(),
            cleanText(header.returnRemark(), 1000),
            itemRows.stream().limit(detailItemLimit).map(this::mapItem).toList(),
            exchangeRows.stream().limit(detailItemLimit).map(this::mapExchangeGoods).toList(),
            mapRefund(refund), itemRows.size() > detailItemLimit,
            exchangeRows.size() > detailItemLimit, OffsetDateTime.now());
}
```

`mapRefund(null)` 返回五个金额均为 `null` 的对象；所有非空金额必须大于等于 0，否则抛出非法响应异常。详情 DTO 中不得添加 `id`、`afterSaleId`、`customerId`、`bankAccount`、`accountName`、`detailAddress`、`advancedPaymentReceiptUrls`。

- [ ] **Step 6: 运行详情测试并做字段泄露断言**

Run: `mvn -pl AfterSale -Dtest=AgentAfterSaleDetailServiceTest test`

Expected: PASS；序列化 JSON 不包含 `afterSaleId`、`bankAccount`、`detailAddress`。

- [ ] **Step 7: 提交详情能力**

```powershell
git add AfterSale/src/main/java/com/xjjk/ec/AfterSale/agent/api/AgentAfterSaleDetailRequest.java AfterSale/src/main/java/com/xjjk/ec/AfterSale/agent/api/AgentAfterSaleDetailResponse.java AfterSale/src/main/java/com/xjjk/ec/AfterSale/agent/dao/AgentAfterSaleMapper.java AfterSale/src/main/java/com/xjjk/ec/AfterSale/agent/service/AgentAfterSaleDetailService.java AfterSale/src/test/java/com/xjjk/ec/AfterSale/agent/service/AgentAfterSaleDetailServiceTest.java
git commit -m "feat: add scoped agent after-sale detail"
```

### Task 4: 发布 aftersale 内部 Controller 契约

**Files:**
- Create: `D:\GitCode\aftersale\AfterSale\src\main\java\com\xjjk\ec\AfterSale\agent\api\AgentAfterSaleController.java`
- Test: `D:\GitCode\aftersale\AfterSale\src\test\java\com\xjjk\ec\AfterSale\agent\api\AgentAfterSaleControllerTest.java`

- [ ] **Step 1: 写 lowerCamelCase、身份属性和 404 测试**

```java
@WebMvcTest(AgentAfterSaleController.class)
class AgentAfterSaleControllerTest {
    @Autowired MockMvc mvc;
    @MockBean AgentAfterSaleQueryService queryService;
    @MockBean AgentAfterSaleDetailService detailService;

    @Test
    void searchUsesRequestAttributeIdentity() throws Exception {
        when(queryService.search(any(), eq(identity()))).thenReturn(emptySearch());
        mvc.perform(post("/internal/agent/after-sales/search")
                        .requestAttr(AgentAfterSaleIdentity.REQUEST_ATTRIBUTE, identity())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifierType\":\"ORDER_CODE\",\"identifier\":\"XJTS01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.matchedBy").value("ORDER_CODE"))
                .andExpect(jsonPath("$.data.items").isArray());
    }
}
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `mvn -pl AfterSale -Dtest=AgentAfterSaleControllerTest test`

Expected: FAIL，Controller 尚不存在。

- [ ] **Step 3: 实现只读 Controller**

```java
@RestController
@RequestMapping("/internal/agent/after-sales")
@RequiredArgsConstructor
public class AgentAfterSaleController {
    private final AgentAfterSaleQueryService queryService;
    private final AgentAfterSaleDetailService detailService;

    @PostMapping("/search")
    public ReturnMsgVo<AgentAfterSaleSearchResponse> search(
            @RequestBody AgentAfterSaleSearchRequest request,
            HttpServletRequest servletRequest) {
        return ReturnUtil.success(queryService.search(request, identity(servletRequest)));
    }

    @PostMapping("/detail")
    public ReturnMsgVo<AgentAfterSaleDetailResponse> detail(
            @RequestBody AgentAfterSaleDetailRequest request,
            HttpServletRequest servletRequest) {
        return ReturnUtil.success(detailService.detail(request, identity(servletRequest)));
    }

    private AgentAfterSaleIdentity identity(HttpServletRequest request) {
        Object value = request.getAttribute(AgentAfterSaleIdentity.REQUEST_ATTRIBUTE);
        if (!(value instanceof AgentAfterSaleIdentity identity)) {
            throw new AccessDeniedException("缺少可信身份");
        }
        return identity;
    }
}
```

Controller 导入 `com.xjjk.devengine.assistant.core.models.vo.ReturnMsgVo` 和 `com.xjjk.devengine.assistant.core.utils.ReturnUtil`，保持项目现有统一响应格式；Agent 侧响应包装同时兼容 `Code/Msg/Data` 与 lowerCamelCase 包装字段。

- [ ] **Step 4: 运行 aftersale 新增测试与编译**

Run: `mvn -pl AfterSale -Dtest='AgentAfterSale*Test' test`

Expected: PASS，所有 Agent 售后测试通过。

Run: `mvn -pl AfterSale -DskipTests package`

Expected: BUILD SUCCESS。

- [ ] **Step 5: 提交 Controller**

```powershell
git add AfterSale/src/main/java/com/xjjk/ec/AfterSale/agent/api/AgentAfterSaleController.java AfterSale/src/test/java/com/xjjk/ec/AfterSale/agent/api/AgentAfterSaleControllerTest.java
git commit -m "feat: expose agent after-sale read api"
```

### Task 5: 在 Agent Server 建立售后领域契约、Feign 和韧性 Gateway

**Files:**
- Create: `D:\GitCode\order-logistics-agent-server\src\main\java\com\xjjk\agent\aftersale\domain\AfterSaleIdentifierType.java`
- Create: `D:\GitCode\order-logistics-agent-server\src\main\java\com\xjjk\agent\aftersale\domain\AfterSaleSearchResult.java`
- Create: `D:\GitCode\order-logistics-agent-server\src\main\java\com\xjjk\agent\aftersale\domain\AfterSaleDetailResult.java`
- Create: `D:\GitCode\order-logistics-agent-server\src\main\java\com\xjjk\agent\aftersale\service\AfterSaleQueryGateway.java`
- Create: `D:\GitCode\order-logistics-agent-server\src\main\java\com\xjjk\agent\aftersale\service\AfterSaleServiceUnavailableException.java`
- Create: `D:\GitCode\order-logistics-agent-server\src\main\java\com\xjjk\agent\aftersale\client\AfterSaleClient.java`
- Create: `D:\GitCode\order-logistics-agent-server\src\main\java\com\xjjk\agent\aftersale\client\AfterSaleFeignConfiguration.java`
- Create: `D:\GitCode\order-logistics-agent-server\src\main\java\com\xjjk\agent\aftersale\client\AfterSaleServiceGateway.java`
- Create: `D:\GitCode\order-logistics-agent-server\src\main\java\com\xjjk\agent\aftersale\config\AfterSaleIntegrationProperties.java`
- Create: `D:\GitCode\order-logistics-agent-server\src\main\java\com\xjjk\agent\aftersale\config\AfterSaleCircuitBreakerConfiguration.java`
- Test: `D:\GitCode\order-logistics-agent-server\src\test\java\com\xjjk\agent\aftersale\client\AfterSaleServiceGatewayTest.java`

- [ ] **Step 1: 写请求头、映射、重试和非法响应测试**

```java
@Test
void searchPassesTrustedIdentityAndMapsFiveItems() {
    when(client.search(eq("token"), eq(1L), eq(10567L), eq(20L), eq(REQUEST_ID), any()))
            .thenReturn(success(searchData(6, true, 5)));
    AfterSaleSearchResult result = gateway.search(
            AfterSaleIdentifierType.CUSTOMER_CODE, "C24101816040001", null, null,
            new AgentIdentity(10567, "agent", "测试坐席", 20, 1), REQUEST_ID);
    assertEquals(6, result.total());
    assertEquals(5, result.items().size());
}

@Test
void retriesHttp503Once() {
    when(client.search(any(), anyLong(), anyLong(), anyLong(), any(), any()))
            .thenThrow(FeignException.errorStatus("search",
                    feign.Response.builder().status(503).reason("Unavailable")
                            .request(feign.Request.create(feign.Request.HttpMethod.POST,
                                    "/internal/agent/after-sales/search", Map.of(), null,
                                    StandardCharsets.UTF_8, null)).build()))
            .thenReturn(success(searchData(0, false, 0)));
    gateway.search(AfterSaleIdentifierType.ORDER_CODE, "XJTS01", null, null, identity, REQUEST_ID);
    verify(client, times(2)).search(any(), anyLong(), anyLong(), anyLong(), any(), any());
}

@Test
void rejectsMoreThanFiveSearchItems() {
    when(client.search(any(), anyLong(), anyLong(), anyLong(), any(), any()))
            .thenReturn(success(searchData(6, false, 6)));
    assertThrows(AfterSaleServiceUnavailableException.class, () ->
            gateway.search(AfterSaleIdentifierType.ORDER_CODE, "XJTS01", null, null, identity, REQUEST_ID));
}
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `.\mvnw.cmd -Dtest=AfterSaleServiceGatewayTest test`

Expected: FAIL，售后 Gateway 类型不存在。

- [ ] **Step 3: 定义 Feign 客户端与应用 Gateway 接口**

```java
@FeignClient(
        name = "agent-after-sale",
        url = "${integration.aftersale.base-url}",
        configuration = AfterSaleFeignConfiguration.class)
public interface AfterSaleClient {
    @PostMapping("/internal/agent/after-sales/search")
    Response<SearchData> search(
            @RequestHeader("X-Agent-Internal-Token") String token,
            @RequestHeader("X-Agent-Tenant-Id") long tenantId,
            @RequestHeader("X-Agent-User-Id") long userId,
            @RequestHeader("X-Agent-Org-Id") long orgId,
            @RequestHeader("X-Agent-Request-Id") String requestId,
            @RequestBody SearchRequest request);

    @PostMapping("/internal/agent/after-sales/detail")
    Response<DetailData> detail(
            @RequestHeader("X-Agent-Internal-Token") String token,
            @RequestHeader("X-Agent-Tenant-Id") long tenantId,
            @RequestHeader("X-Agent-User-Id") long userId,
            @RequestHeader("X-Agent-Org-Id") long orgId,
            @RequestHeader("X-Agent-Request-Id") String requestId,
            @RequestBody DetailRequest request);
}
```

新增 `AfterSaleServiceResponse<T>`，明确兼容旧服务包装的大小写，但业务数据始终使用 lowerCamelCase 强类型 record：

```java
@JsonIgnoreProperties(ignoreUnknown = true)
public record AfterSaleServiceResponse<T>(
        @JsonProperty("code") @JsonAlias("Code") Integer code,
        @JsonProperty("message") @JsonAlias({"Message", "msg", "Msg"}) String message,
        @JsonProperty("data") @JsonAlias("Data") T data) {}
```

`AfterSaleClient` 内的请求和响应必须是以下强类型 record，不得使用 `Map<String,Object>`：

```java
record SearchRequest(
        AfterSaleIdentifierType identifierType, String identifier,
        OffsetDateTime startTime, OffsetDateTime endTime) {}
record DetailRequest(String afterSaleCode) {}
@JsonIgnoreProperties(ignoreUnknown = true)
record SearchData(
        String matchedBy, Long total, Boolean truncated,
        OffsetDateTime queriedAt, List<SearchItemData> items) {}
@JsonIgnoreProperties(ignoreUnknown = true)
record SearchItemData(
        String afterSaleCode, Integer statusCode, String statusText,
        OffsetDateTime createdAt, Boolean finished, OffsetDateTime finishedAt,
        String customerDisplayName, String customerCode, String orderCode,
        String returnLogisticsCode, String assigneeDisplayName) {}
@JsonIgnoreProperties(ignoreUnknown = true)
record DetailData(
        String afterSaleCode, Integer statusCode, String statusText,
        OffsetDateTime createdAt, Boolean finished, OffsetDateTime finishedAt,
        String customerDisplayName, String customerCode, String orderCode,
        String exchangeOrderCode, String returnLogisticsCode, String returnRemark,
        List<ItemData> items, List<ExchangeGoodsData> exchangeGoods,
        RefundSummaryData refundSummary, Boolean itemsTruncated,
        Boolean exchangeGoodsTruncated, OffsetDateTime queriedAt) {}
@JsonIgnoreProperties(ignoreUnknown = true)
record ItemData(
        String goodsName, String skuCode, String specification, String reason,
        Integer originalQuantity, Integer receivedQuantity, Integer refundQuantity,
        Integer exchangeQuantity, Integer sentBackQuantity) {}
@JsonIgnoreProperties(ignoreUnknown = true)
record ExchangeGoodsData(
        String goodsName, String skuCode, Integer quantity,
        Long unitPriceInFen, Long subtotalInFen) {}
@JsonIgnoreProperties(ignoreUnknown = true)
record RefundSummaryData(
        Long totalCashInFen, Long totalPreStorageInFen,
        Long returnPreStorageInFen, Long returnCouponInFen,
        Long returnIntegral) {}
```

Agent 领域对象使用相同字段，但不复用 Feign 类型。搜索对象定义为：

```java
public record AfterSaleSearchResult(
        AfterSaleIdentifierType matchedBy, long total, boolean truncated,
        OffsetDateTime queriedAt, List<Item> items) {
    public record Item(
            String afterSaleCode, Integer statusCode, String statusText,
            OffsetDateTime createdAt, boolean finished, OffsetDateTime finishedAt,
            String customerDisplayName, String customerCode, String orderCode,
            String returnLogisticsCode, String assigneeDisplayName) {}
}
```

详情对象定义为：

```java
public record AfterSaleDetailResult(
        String afterSaleCode, Integer statusCode, String statusText,
        OffsetDateTime createdAt, boolean finished, OffsetDateTime finishedAt,
        String customerDisplayName, String customerCode, String orderCode,
        String exchangeOrderCode, String returnLogisticsCode, String returnRemark,
        List<Item> items, List<ExchangeGoods> exchangeGoods,
        RefundSummary refundSummary, boolean itemsTruncated,
        boolean exchangeGoodsTruncated, OffsetDateTime queriedAt) {
    public record Item(
            String goodsName, String skuCode, String specification, String reason,
            Integer originalQuantity, Integer receivedQuantity, Integer refundQuantity,
            Integer exchangeQuantity, Integer sentBackQuantity) {}
    public record ExchangeGoods(
            String goodsName, String skuCode, Integer quantity,
            Long unitPriceInFen, Long subtotalInFen) {}
    public record RefundSummary(
            Long totalCashInFen, Long totalPreStorageInFen,
            Long returnPreStorageInFen, Long returnCouponInFen,
            Long returnIntegral) {}
}
```

`AfterSaleQueryGateway` 暴露：

```java
AfterSaleSearchResult search(
        AfterSaleIdentifierType type, String identifier,
        OffsetDateTime startTime, OffsetDateTime endTime,
        AgentIdentity identity, String requestId);

AfterSaleDetailResult detail(
        String afterSaleCode, AgentIdentity identity, String requestId);
```

`AfterSaleIntegrationProperties` 固定映射 `integration.aftersale`：

```java
@Data
@Validated
@ConfigurationProperties(prefix = "integration.aftersale")
public class AfterSaleIntegrationProperties {
    @NotBlank private String baseUrl;
    @NotBlank private String internalToken;
    @Valid private Resilience resilience = new Resilience();

    @Data
    public static class Resilience {
        @Min(2) private int slidingWindowSize = 20;
        @Min(1) private int minimumNumberOfCalls = 10;
        @DecimalMin("1") @DecimalMax("100") private float failureRateThreshold = 50;
        @Min(1) private int permittedCallsInHalfOpenState = 3;
        @NotNull private Duration openStateWaitDuration = Duration.ofSeconds(30);
        @NotNull private Duration callTimeout = Duration.ofSeconds(4);
    }
}
```

- [ ] **Step 4: 实现禁用 Feign 自动重试、显式一次重试和两个独立熔断器**

```java
public class AfterSaleFeignConfiguration {
    @Bean Retryer retryer() { return Retryer.NEVER_RETRY; }
}
```

```java
public static final String SEARCH = "agentAfterSaleSearch";
public static final String DETAIL = "agentAfterSaleDetail";

@Bean
Customizer<Resilience4JCircuitBreakerFactory> afterSaleCircuitBreakers(
        AfterSaleIntegrationProperties properties) {
    return factory -> factory.configure(builder -> builder
            .circuitBreakerConfig(CircuitBreakerConfig.custom()
                    .slidingWindowSize(properties.getResilience().getSlidingWindowSize())
                    .minimumNumberOfCalls(properties.getResilience().getMinimumNumberOfCalls())
                    .failureRateThreshold(properties.getResilience().getFailureRateThreshold())
                    .recordException(AfterSaleServiceUnavailableException.class::isInstance)
                    .build())
            .timeLimiterConfig(TimeLimiterConfig.custom()
                    .timeoutDuration(properties.getResilience().getCallTimeout())
                    .cancelRunningFuture(true).build()), SEARCH, DETAIL);
}
```

`AfterSaleServiceGateway` 的调用和重试核心实现为：

```java
private <T> T invokeWithRetry(String operation, String requestId, Supplier<T> invocation) {
    for (int attempt = 1; attempt <= 2; attempt++) {
        try {
            return invocation.get();
        } catch (RuntimeException exception) {
            boolean retryable = isTransientFailure(exception);
            if (!retryable || attempt == 2) {
                log.warn("after_sale_gateway requestId={}, operation={}, attempt={}, status=FAILED, failureCategory={}",
                        requestId, operation, attempt, failureCategory(exception));
                throw new AfterSaleServiceUnavailableException("售后服务调用失败");
            }
            log.warn("after_sale_gateway requestId={}, operation={}, attempt={}, status=RETRYING, failureCategory={}",
                    requestId, operation, attempt, failureCategory(exception));
        }
    }
    throw new IllegalStateException("unreachable");
}

private boolean isTransientFailure(RuntimeException exception) {
    if (exception instanceof FeignException feign && feign.status() > 0) {
        return feign.status() == 502 || feign.status() == 503 || feign.status() == 504;
    }
    if (!(exception instanceof RetryableException retryable) || retryable.status() >= 0) {
        return false;
    }
    for (Throwable cause = retryable.getCause(); cause != null; cause = cause.getCause()) {
        if (cause instanceof UnknownHostException || cause instanceof SSLException
                || cause instanceof ProtocolException) return false;
        if (cause instanceof ConnectException || cause instanceof SocketTimeoutException
                || cause instanceof ConnectionRequestTimeoutException) return true;
    }
    return false;
}
```

`search(...)` 在进入熔断器前校验参数，再在 `agentAfterSaleSearch` 熔断器中调用 `client.search(...)` 和 `mapSearchResponse(...)`；`detail(...)` 同样使用独立的 `agentAfterSaleDetail` 熔断器。`mapSearchResponse` 明确校验 `items != null`、`items.size() <= 5`、`total >= items.size()`，且 `truncated=false` 时 `total == items.size()`；`mapDetailResponse` 明确校验两组集合非 null 且各不超过 20。跨 Gateway 边界只抛出没有原始 cause 的 `AfterSaleServiceUnavailableException("售后服务调用失败")`。未知状态文本为空时替换为 `状态未知`，warn 日志只写 `requestId/operation/statusCode`。

- [ ] **Step 5: 运行 Gateway 测试**

Run: `.\mvnw.cmd -Dtest=AfterSaleServiceGatewayTest test`

Expected: PASS；503 恰好调用 2 次，400/401/403/404 恰好调用 1 次。

- [ ] **Step 6: 提交 Agent Gateway**

```powershell
git add src/main/java/com/xjjk/agent/aftersale src/test/java/com/xjjk/agent/aftersale/client/AfterSaleServiceGatewayTest.java
git commit -m "feat: add resilient after-sale gateway"
```

### Task 6: 实现售后模型工具、灰度与 SSE 完整结果

**Files:**
- Create: `D:\GitCode\order-logistics-agent-server\src\main\java\com\xjjk\agent\aftersale\tool\AfterSaleToolAvailability.java`
- Create: `D:\GitCode\order-logistics-agent-server\src\main\java\com\xjjk\agent\aftersale\tool\AfterSaleQueryTools.java`
- Test: `D:\GitCode\order-logistics-agent-server\src\test\java\com\xjjk\agent\aftersale\tool\AfterSaleToolAvailabilityTest.java`
- Test: `D:\GitCode\order-logistics-agent-server\src\test\java\com\xjjk\agent\aftersale\tool\AfterSaleQueryToolsTest.java`

- [ ] **Step 1: 写 OFF/ALLOWLIST/ALL 和工具输出分流测试**

```java
@Test
void publishesFullSearchResultButReturnsBoundedModelFacts() {
    when(gateway.search(any(), any(), any(), any(), eq(identity), eq(REQUEST_ID)))
            .thenReturn(searchResult());
    String text = tools.searchAfterSales(
            "CUSTOMER_CODE", "C24101816040001", null, null, toolContext());
    assertTrue(text.contains("匹配总数=6"));
    assertTrue(text.length() <= 1900);
    verify(publisher).publish(argThat(result ->
            result.toolName().equals("search_after_sales")
                    && result.kind().equals("after-sale-list")
                    && result.data() == searchResult()));
}
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `.\mvnw.cmd -Dtest='AfterSaleToolAvailabilityTest,AfterSaleQueryToolsTest' test`

Expected: FAIL，售后工具类型不存在。

- [ ] **Step 3: 实现独立搜索/详情灰度**

```java
@ConfigurationProperties(prefix = "agent.tool.after-sale")
public record AfterSaleToolAvailability(Capability search, Capability detail) {
    public boolean isSearchAvailable(AgentIdentity identity) { return available(search, identity); }
    public boolean isDetailAvailable(AgentIdentity identity) { return available(detail, identity); }

    public record Capability(boolean enabled, String rolloutMode, Set<Long> allowedOrgIds) {
        public Capability { allowedOrgIds = allowedOrgIds == null ? Set.of() : Set.copyOf(allowedOrgIds); }
    }
}
```

`available` 只允许 `ALL` 或命中组织白名单的 `ALLOWLIST`，空白、未知模式、缺失身份统一返回 false。

- [ ] **Step 4: 实现两个只读模型工具**

```java
@Tool(name = "search_after_sales",
        description = "按售后工单号、原订单号、客户编号或客户姓名查询售后工单；可选售后创建时间范围。")
public String searchAfterSales(
        @ToolParam(description = "AFTER_SALE_CODE、ORDER_CODE、CUSTOMER_CODE、CUSTOMER_NAME") String identifierType,
        @ToolParam(description = "对应的售后工单号、原订单号、客户编号或客户姓名") String identifier,
        @ToolParam(description = "可选开始时间，ISO-8601", required = false) String startTime,
        @ToolParam(description = "可选结束时间，ISO-8601", required = false) String endTime,
        ToolContext toolContext) {
    ParsedSearch arguments = parseSearch(identifierType, identifier, startTime, endTime);
    if (arguments.error() != null) return arguments.error();
    AgentToolRequestContext context = requestContext(toolContext);
    if (!availability.isSearchAvailable(context.identity())) return "当前组织暂未开放售后查询能力。";
    try {
        return context.callGuard().execute("search_after_sales", arguments.key(), () -> {
            AfterSaleSearchResult result = gateway.search(arguments.type(), arguments.identifier(),
                    arguments.startTime(), arguments.endTime(), context.identity(), context.requestId());
            context.outputPublisher().publish(new ToolUiResult(
                    "search_after_sales", "after-sale-list", 1, result.queriedAt(), result));
            return boundedSearchText(result);
        });
    } catch (AfterSaleServiceUnavailableException exception) {
        return "售后查询服务暂时不可用，请稍后重试。";
    }
}

@Tool(name = "get_after_sale_detail",
        description = "按完整售后工单号查询售后详情、商品与退款汇总。")
public String getAfterSaleDetail(
        @ToolParam(description = "完整售后工单号") String afterSaleCode,
        ToolContext toolContext) {
    String code = normalizeCode(afterSaleCode);
    AgentToolRequestContext context = requestContext(toolContext);
    if (!availability.isDetailAvailable(context.identity())) return "当前组织暂未开放售后详情能力。";
    try {
        return context.callGuard().execute("get_after_sale_detail", code, () -> {
            AfterSaleDetailResult result = gateway.detail(code, context.identity(), context.requestId());
            context.outputPublisher().publish(new ToolUiResult(
                    "get_after_sale_detail", "after-sale-detail", 1, result.queriedAt(), result));
            return boundedDetailText(result);
        });
    } catch (AfterSaleServiceUnavailableException exception) {
        return "售后详情服务暂时不可用，请稍后重试。";
    }
}
```

模型搜索文本只含查询时间、匹配总数、展示数、售后工单号、状态和截断提示；详情文本只含工单号、状态、商品行数和“前端已展示详情”，总长度不超过 1900，不拼接售后说明或商品原因。

- [ ] **Step 5: 运行工具测试**

Run: `.\mvnw.cmd -Dtest='AfterSaleToolAvailabilityTest,AfterSaleQueryToolsTest' test`

Expected: PASS；SSE 获得完整领域对象，模型文本中不出现 `returnRemark`。

- [ ] **Step 6: 提交模型工具**

```powershell
git add src/main/java/com/xjjk/agent/aftersale/tool/AfterSaleToolAvailability.java src/main/java/com/xjjk/agent/aftersale/tool/AfterSaleQueryTools.java src/test/java/com/xjjk/agent/aftersale/tool/AfterSaleToolAvailabilityTest.java src/test/java/com/xjjk/agent/aftersale/tool/AfterSaleQueryToolsTest.java
git commit -m "feat: add after-sale ai tools"
```

### Task 7: 增加“查看售后详情”白名单动作和持久化契约

**Files:**
- Modify: `D:\GitCode\order-logistics-agent-server\src\main\java\com\xjjk\agent\chat\action\ChatActionType.java`
- Modify: `D:\GitCode\order-logistics-agent-server\src\main\java\com\xjjk\agent\chat\api\dto\ChatActionRequest.java`
- Modify: `D:\GitCode\order-logistics-agent-server\src\main\java\com\xjjk\agent\chat\action\ChatActionDispatcher.java`
- Modify: `D:\GitCode\order-logistics-agent-server\src\test\java\com\xjjk\agent\chat\action\ChatActionDispatcherTest.java`
- Modify: `D:\GitCode\order-logistics-agent-server\src\test\java\com\xjjk\agent\chat\api\dto\ChatStreamBusinessResultContractTest.java`

- [ ] **Step 1: 写动作绕过模型但仍经过灰度和 Gateway 的测试**

```java
@Test
void queryAfterSaleDetailPublishesNewAssistantResult() {
    when(afterSaleAvailability.isDetailAvailable(identity)).thenReturn(true);
    when(afterSaleGateway.detail("AS202609080001", identity, REQUEST_ID)).thenReturn(detailResult);
    ChatActionDispatcher.DispatchResult result = dispatcher.dispatch(
            new ChatActionRequest("QUERY_AFTER_SALE_DETAIL", null, null, "AS202609080001"),
            identity, REQUEST_ID);
    assertEquals("after-sale-detail", result.uiResult().kind());
    assertEquals("AS202609080001",
            ((AfterSaleDetailResult) result.uiResult().data()).afterSaleCode());
    assertTrue(result.assistantText().contains("售后工单 AS202609080001"));
}
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `.\mvnw.cmd -Dtest='ChatActionDispatcherTest,ChatStreamBusinessResultContractTest' test`

Expected: FAIL，动作枚举和请求字段尚未定义。

- [ ] **Step 3: 扩展严格动作白名单和请求 DTO**

```java
public enum ChatActionType {
    QUERY_ORDER_LOGISTICS,
    QUERY_CUSTOMER_ORDERS,
    QUERY_AFTER_SALE_DETAIL;
}
```

```java
public record ChatActionRequest(
        @NotBlank @Size(max = 64) String type,
        @Size(max = 64) String orderCode,
        @Size(max = 128) String customerCode,
        @Size(max = 64) String afterSaleCode) {
    public ChatActionRequest(String type, String orderCode) {
        this(type, orderCode, null, null);
    }
    public ChatActionRequest(String type, String orderCode, String customerCode) {
        this(type, orderCode, customerCode, null);
    }
}
```

- [ ] **Step 4: 在 Dispatcher 中直接查询详情**

```java
case QUERY_AFTER_SALE_DETAIL -> queryAfterSaleDetail(
        action.afterSaleCode(), identity, requestId);
```

```java
private DispatchResult queryAfterSaleDetail(
        String afterSaleCode, AgentIdentity identity, String requestId) {
    if (!afterSaleAvailability.isDetailAvailable(identity)) {
        throw new BusinessException(ApiErrorCode.CHAT_ACTION_UNAVAILABLE);
    }
    String code = normalizeAfterSaleCode(afterSaleCode);
    try {
        AfterSaleDetailResult result = afterSaleGateway.detail(code, identity, requestId);
        return new DispatchResult(
                new ToolUiResult("get_after_sale_detail", "after-sale-detail", 1,
                        result.queriedAt(), result),
                "已为你查询售后工单 " + code + " 的详情，详细信息已展示。");
    } catch (AfterSaleServiceUnavailableException exception) {
        log.warn("chat_action_failed requestId={}, action={}, exceptionType={}",
                requestId, ChatActionType.QUERY_AFTER_SALE_DETAIL,
                exception.getClass().getSimpleName());
        throw new BusinessException(ApiErrorCode.CHAT_ACTION_UNAVAILABLE);
    }
}
```

`normalizeAfterSaleCode` 必须 strip 后校验非空、长度不超过 64、不得含控制字符。沿用当前 `ChatActionDispatcher` 的收尾路径，使 `ToolUiResult` 与固定回答一起形成新的助手消息并写入 `agent_message_result`；不得更新原列表消息。

- [ ] **Step 5: 运行动作与结果恢复测试**

Run: `.\mvnw.cmd -Dtest='ChatActionDispatcherTest,ChatStreamBusinessResultContractTest,AgentMessageResultQueryServiceTest' test`

Expected: PASS；详情动作结果可从历史消息恢复为 `after-sale-detail`。

- [ ] **Step 6: 提交动作链路**

```powershell
git add src/main/java/com/xjjk/agent/chat/action/ChatActionType.java src/main/java/com/xjjk/agent/chat/api/dto/ChatActionRequest.java src/main/java/com/xjjk/agent/chat/action/ChatActionDispatcher.java src/test/java/com/xjjk/agent/chat/action/ChatActionDispatcherTest.java src/test/java/com/xjjk/agent/chat/api/dto/ChatStreamBusinessResultContractTest.java
git commit -m "feat: add after-sale detail card action"
```

### Task 8: 扩展桌面端契约、IPC 和 Store 动作

**Files:**
- Modify: `D:\GitCode\order-logistics-agent-web\src\shared\desktop.ts`
- Modify: `D:\GitCode\order-logistics-agent-web\src\main\agent-chat-stream-client.ts`
- Modify: `D:\GitCode\order-logistics-agent-web\src\main\agent-chat-stream-client.test.ts`
- Modify: `D:\GitCode\order-logistics-agent-web\src\renderer\src\contracts\chat.ts`
- Modify: `D:\GitCode\order-logistics-agent-web\src\renderer\src\stores\chat.ts`
- Test: `D:\GitCode\order-logistics-agent-web\src\renderer\src\stores\chat.test.ts`

- [ ] **Step 1: 写 IPC 白名单和 Store 文案测试**

```ts
it('rebuilds a clean whitelisted after-sale detail action before sending HTTP', async () => {
  let body: unknown
  const fetcher: typeof fetch = async (_input, init) => {
    body = JSON.parse(String(init?.body))
    return new Response('event:done\ndata:{}\n\n', { status: 200 })
  }
  const auth = { getAccessToken: async () => 'main-process-token' } as AuthSession
  const client = new AgentChatStreamClient('http://agent', auth, fetcher)
  await client.start({
    message: '查看售后工单 AS202609080001 的详情',
    action: { type: 'QUERY_AFTER_SALE_DETAIL', afterSaleCode: ' AS202609080001 ' }
  })
  expect(body).toEqual({
    message: '查看售后工单 AS202609080001 的详情',
    action: { type: 'QUERY_AFTER_SALE_DETAIL', afterSaleCode: 'AS202609080001' }
  })
})

it('sends deterministic after-sale detail action', async () => {
  await store.sendAction({ type: 'QUERY_AFTER_SALE_DETAIL', afterSaleCode: ' AS202609080001 ' })
  expect(transport.lastRequest()).toMatchObject({
    message: '查看售后工单 AS202609080001 的详情',
    action: { type: 'QUERY_AFTER_SALE_DETAIL', afterSaleCode: 'AS202609080001' }
  })
})
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `npm test -- --run src/main/agent-chat-stream-client.test.ts src/renderer/src/stores/chat.test.ts`

Expected: FAIL，TypeScript 联合类型不接受新动作。

- [ ] **Step 3: 扩展共享动作类型和严格 IPC 校验**

```ts
export type ChatAction =
  | { type: 'QUERY_ORDER_LOGISTICS'; orderCode: string }
  | { type: 'QUERY_CUSTOMER_ORDERS'; customerCode: string }
  | { type: 'QUERY_AFTER_SALE_DETAIL'; afterSaleCode: string }
```

```ts
if (value.type === 'QUERY_AFTER_SALE_DETAIL') {
  const afterSaleCode = typeof value.afterSaleCode === 'string' ? value.afterSaleCode.trim() : ''
  if (!afterSaleCode || afterSaleCode.length > 64 || /[\u0000-\u001f\u007f]/u.test(afterSaleCode)) {
    throw new Error('售后工单号不合法')
  }
  return { type: 'QUERY_AFTER_SALE_DETAIL', afterSaleCode }
}
```

- [ ] **Step 4: 定义前端强类型业务结果**

```ts
export interface AfterSaleSearchItem {
  afterSaleCode: string
  statusCode: number | null
  statusText: string
  createdAt: string
  finished: boolean
  finishedAt: string | null
  customerDisplayName: string
  customerCode: string | null
  orderCode: string | null
  returnLogisticsCode: string | null
  assigneeDisplayName: string | null
}

export interface AfterSaleSearchData {
  matchedBy: 'AFTER_SALE_CODE' | 'ORDER_CODE' | 'CUSTOMER_CODE' | 'CUSTOMER_NAME'
  total: number
  truncated: boolean
  queriedAt: string
  items: AfterSaleSearchItem[]
}

export interface AfterSaleDetailData {
  afterSaleCode: string
  statusCode: number | null
  statusText: string
  createdAt: string
  finished: boolean
  finishedAt: string | null
  customerDisplayName: string
  customerCode: string | null
  orderCode: string | null
  exchangeOrderCode: string | null
  returnLogisticsCode: string | null
  returnRemark: string | null
  items: Array<{ goodsName: string; skuCode: string | null; specification: string | null; reason: string | null; originalQuantity: number | null; receivedQuantity: number | null; refundQuantity: number | null; exchangeQuantity: number | null; sentBackQuantity: number | null }>
  exchangeGoods: Array<{ goodsName: string; skuCode: string | null; quantity: number; unitPriceInFen: number | null; subtotalInFen: number | null }>
  refundSummary: { totalCashInFen: number | null; totalPreStorageInFen: number | null; returnPreStorageInFen: number | null; returnCouponInFen: number | null; returnIntegral: number | null }
  itemsTruncated: boolean
  exchangeGoodsTruncated: boolean
  queriedAt: string
}

export type AfterSaleListResult = ResultBase<'after-sale-list', AfterSaleSearchData>
export type AfterSaleDetailResult = ResultBase<'after-sale-detail', AfterSaleDetailData>
export type ChatResult = ProductListResult | CustomerListResult | OrderListResult |
  LogisticsTimelineResult | AfterSaleListResult | AfterSaleDetailResult
```

- [ ] **Step 5: 在 Store 中发送确定性动作**

```ts
if (action.type === 'QUERY_AFTER_SALE_DETAIL') {
  const afterSaleCode = action.afterSaleCode.trim()
  if (!afterSaleCode || afterSaleCode.length > 64) return
  await sendRequest(`查看售后工单 ${afterSaleCode} 的详情`, {
    type: 'QUERY_AFTER_SALE_DETAIL',
    afterSaleCode
  })
  return
}
```

- [ ] **Step 6: 运行契约、IPC 和 Store 测试**

Run: `npm test -- --run src/main/agent-chat-stream-client.test.ts src/renderer/src/stores/chat.test.ts`

Expected: PASS，新动作只携带工单号，不携带身份或内部 ID。

- [ ] **Step 7: 提交桌面端动作契约**

```powershell
git add src/shared/desktop.ts src/main/agent-chat-stream-client.ts src/main/agent-chat-stream-client.test.ts src/renderer/src/contracts/chat.ts src/renderer/src/stores/chat.ts src/renderer/src/stores/chat.test.ts
git commit -m "feat: add after-sale card contracts"
```

### Task 9: 实现售后列表和详情卡片

**Files:**
- Create: `D:\GitCode\order-logistics-agent-web\src\renderer\src\components\AfterSaleListCard.vue`
- Create: `D:\GitCode\order-logistics-agent-web\src\renderer\src\components\AfterSaleListCard.test.ts`
- Create: `D:\GitCode\order-logistics-agent-web\src\renderer\src\components\AfterSaleDetailCard.vue`
- Create: `D:\GitCode\order-logistics-agent-web\src\renderer\src\components\AfterSaleDetailCard.test.ts`
- Modify: `D:\GitCode\order-logistics-agent-web\src\renderer\src\components\ChatWindow.vue`

- [ ] **Step 1: 写列表总数、展示数和按钮参数测试**

```ts
it('shows total and displayed count and emits clicked code', async () => {
  const wrapper = mount(AfterSaleListCard, { props: { result, disabled: false } })
  expect(wrapper.text()).toContain('共 6 条 · 当前展示 5 条')
  await wrapper.findAll('button')[1].trigger('click')
  expect(wrapper.emitted('action')?.[0]).toEqual([
    { type: 'QUERY_AFTER_SALE_DETAIL', afterSaleCode: result.data.items[1].afterSaleCode }
  ])
})
```

- [ ] **Step 2: 写详情分区、空值、金额和截断测试**

```ts
it('renders safe detail sections and fen amounts', () => {
  const wrapper = mount(AfterSaleDetailCard, { props: { result } })
  expect(wrapper.text()).toContain('基本信息')
  expect(wrapper.text()).toContain('售后说明')
  expect(wrapper.text()).toContain('原单售后商品')
  expect(wrapper.text()).toContain('换货商品')
  expect(wrapper.text()).toContain('退款汇总')
  expect(wrapper.text()).toContain('¥12.00')
  expect(wrapper.html()).not.toContain('v-html')
})
```

- [ ] **Step 3: 运行组件测试并确认失败**

Run: `npm test -- --run src/renderer/src/components/AfterSaleListCard.test.ts src/renderer/src/components/AfterSaleDetailCard.test.ts`

Expected: FAIL，两个组件尚不存在。

- [ ] **Step 4: 实现列表卡片**

组件必须：

```vue
<header class="after-sale-header">
  <div><strong>售后查询结果</strong><span>共 {{ result.data.total }} 条 · 当前展示 {{ result.data.items.length }} 条</span></div>
</header>
<p v-if="result.data.items.length === 0" class="empty">没有找到匹配售后单</p>
<article v-for="item in result.data.items" :key="item.afterSaleCode" class="after-sale-item">
  <div class="title"><strong>{{ item.afterSaleCode }}</strong><span>{{ item.statusText || '状态未知' }}</span></div>
  <dl>
    <div><dt>创建时间</dt><dd>{{ formatDateTime(item.createdAt) }}</dd></div>
    <div><dt>结案状态</dt><dd>{{ item.finished ? '已结案' : '处理中' }}</dd></div>
    <div><dt>客户</dt><dd>{{ item.customerDisplayName }}（{{ item.customerCode || '未提供' }}）</dd></div>
    <div><dt>原订单</dt><dd>{{ item.orderCode || '未提供' }}</dd></div>
    <div><dt>退货运单</dt><dd>{{ item.returnLogisticsCode || '未提供' }}</dd></div>
    <div><dt>处理人</dt><dd>{{ item.assigneeDisplayName || '未提供' }}</dd></div>
  </dl>
  <button type="button" :disabled="disabled" @click="$emit('action', { type: 'QUERY_AFTER_SALE_DETAIL', afterSaleCode: item.afterSaleCode })">查看详情</button>
</article>
```

时间格式固定为本地 `YYYY-MM-DD HH:mm:ss`；工单号、订单号和运单号使用 `overflow-wrap:anywhere`；窄窗口两列 `dl` 在 420px 以下切成单列。

- [ ] **Step 5: 实现详情卡片**

`AfterSaleDetailCard.vue` 使用五个原生 `<section>`，数组为空时分别显示“无原单售后商品”和“无换货商品”。数量用 `value ?? '未提供'`，不可使用 `value || '未提供'`，避免把 0 显示成空。金额函数固定为：

```ts
function formatFen(value: number | null): string {
  return value == null ? '未提供' : `¥${(value / 100).toFixed(2)}`
}
```

说明、原因、SKU、工单号均用文本插值，不使用 `v-html`。当 `itemsTruncated` 或 `exchangeGoodsTruncated` 为 true 时，在对应分区末尾显示“仅展示前 20 条”。业务卡片不展示 `queriedAt`。

- [ ] **Step 6: 在 ChatWindow 路由两种新结果**

```vue
<AfterSaleListCard
  v-else-if="result.kind === 'after-sale-list'"
  :result="result"
  :disabled="chat.isBusy"
  @action="chat.sendAction"
/>
<AfterSaleDetailCard
  v-else-if="result.kind === 'after-sale-detail'"
  :result="result"
/>
```

- [ ] **Step 7: 运行组件测试、类型检查和构建**

Run: `npm test -- --run src/renderer/src/components/AfterSaleListCard.test.ts src/renderer/src/components/AfterSaleDetailCard.test.ts`

Expected: PASS。

Run: `npm run typecheck`

Expected: PASS，无 TypeScript/Vue 类型错误。

Run: `npm run build`

Expected: PASS，Electron 渲染端和主进程构建完成。

- [ ] **Step 8: 提交卡片**

```powershell
git add src/renderer/src/components/AfterSaleListCard.vue src/renderer/src/components/AfterSaleListCard.test.ts src/renderer/src/components/AfterSaleDetailCard.vue src/renderer/src/components/AfterSaleDetailCard.test.ts src/renderer/src/components/ChatWindow.vue
git commit -m "feat: render after-sale list and detail cards"
```

### Task 10: 配置 Nacos、全链路验证与安全复核

**Files:**
- Modify externally: aftersale 服务的 Nacos 配置文本
- Modify externally: `order-logistics-agent-server` 的 Nacos 配置文本
- Verify only: all files changed in Tasks 1-9

- [ ] **Step 1: 在 aftersale Nacos 增加内部接口配置**

```properties
# Agent 售后内部只读接口；首次联调前保持 false
agent.aftersale.internal-api.enabled=false
agent.aftersale.internal-api.token=${AGENT_INTERNAL_TOKEN}
agent.aftersale.internal-api.supported-tenant-id=1
agent.aftersale.internal-api.query-limit=5
agent.aftersale.internal-api.detail-item-limit=20
```

- [ ] **Step 2: 在 Agent Server Nacos 增加下游、超时、熔断和灰度配置**

```properties
integration.aftersale.base-url=http://127.0.0.1:8084
integration.aftersale.internal-token=${AGENT_INTERNAL_TOKEN}

spring.cloud.openfeign.client.config.agent-after-sale.connect-timeout=1000
spring.cloud.openfeign.client.config.agent-after-sale.read-timeout=3000
spring.cloud.openfeign.client.config.agent-after-sale.logger-level=basic

integration.aftersale.resilience.sliding-window-size=20
integration.aftersale.resilience.minimum-number-of-calls=10
integration.aftersale.resilience.failure-rate-threshold=50
integration.aftersale.resilience.permitted-calls-in-half-open-state=3
integration.aftersale.resilience.open-state-wait-duration=30s
integration.aftersale.resilience.call-timeout=4s

agent.tool.after-sale.search.enabled=false
agent.tool.after-sale.search.rollout-mode=OFF
agent.tool.after-sale.search.allowed-org-ids=
agent.tool.after-sale.detail.enabled=false
agent.tool.after-sale.detail.rollout-mode=OFF
agent.tool.after-sale.detail.allowed-org-ids=
```

联调顺序：先将 aftersale 接口开关改为 true；再把搜索设为 `enabled=true`、`rollout-mode=ALLOWLIST` 并填测试组织；搜索验证通过后才以相同方式开启详情。

- [ ] **Step 3: 跑三个仓库的完整验证**

Run in `D:\GitCode\aftersale`:

```powershell
mvn -pl AfterSale test
```

Expected: BUILD SUCCESS。

Run in `D:\GitCode\order-logistics-agent-server`:

```powershell
.\mvnw.cmd test
```

Expected: BUILD SUCCESS；现有订单、物流、客户、短期记忆和长期摘要测试不回归。

Run in `D:\GitCode\order-logistics-agent-web`:

```powershell
npm test
npm run typecheck
npm run build:unpack
```

Expected: 所有 Vitest 测试通过，类型检查通过，Electron unpack 打包成功。

- [ ] **Step 4: 做契约与敏感字段静态复核**

Run:

```powershell
rg -n "bankAccount|accountName|detailAddress|advancedPaymentReceiptUrls|afterSaleId|customerId" D:\GitCode\order-logistics-agent-server\src\main\java\com\xjjk\agent\aftersale D:\GitCode\order-logistics-agent-web\src\renderer\src\contracts\chat.ts
```

Expected: 无输出；公开契约不含敏感字段和内部 ID。

Run:

```powershell
rg -n "v-html" D:\GitCode\order-logistics-agent-web\src\renderer\src\components\AfterSaleListCard.vue D:\GitCode\order-logistics-agent-web\src\renderer\src\components\AfterSaleDetailCard.vue
```

Expected: 无输出。

- [ ] **Step 5: 按真实测试数据完成人工联调**

依次在新会话输入：

```text
查询售后单 AS202609080001
查询订单 XJTS0120260903000395 的售后
查询客户 C24101816040001 的售后
查询客户张某在 2026-09-01 00:00:00 到 2026-09-08 23:59:59 创建的售后
```

Expected:

- 列表标题显示真实总数和当前展示数，最多 5 条；
- 模型回答只做简短概括，不打印完整表格；
- “查看详情”追加新的助手消息和详情卡片；
- 刷新或重启桌面端后，列表与详情结果均可从历史恢复；
- 其他组织访问得到不可用提示，日志不出现客户姓名、客户编号值、售后说明或 Token；
- aftersale 停止时，搜索/详情返回安全提示，502/503/504 或连接/读取超时只重试一次。

- [ ] **Step 6: 检查提交内容没有混入旧改动**

Run in each repository:

```powershell
git status --short
git log --oneline -8
```

Expected: Tasks 1-9 的提交边界清晰；原先未提交文件仍保持原状态，没有被售后功能提交带入。

## 完成定义

- 四类搜索与可选时间边界都受可信组织范围限制；
- 售后详情只能用权限范围内的售后工单号读取；
- 搜索最多 5 条，详情两组商品各最多 20 条，真实总数和截断标记一致；
- 模型只看到有界最小事实，SSE/历史持久化保存完整脱敏卡片；
- 列表按钮直接走白名单动作，不经过模型，但仍经过灰度、身份、Token、权限、Gateway 和熔断；
- 未知状态安全显示，金额单位为整数分；
- 三个仓库全部测试、类型检查和构建通过；
- Nacos 默认关闭，按搜索后详情的顺序逐步灰度。
