# Rich Order Card Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有订单精确查询链路上增加生产级增强订单卡片，展示可靠金额、收款方式、脱敏收货信息、有界商品明细和多包裹概要，同时兼容历史旧卡片。

**Architecture:** `order` 继续作为订单事实、权限和脱敏边界，以一次内部查询返回向后兼容的增强契约；Agent 严格校验新旧响应，完整结果走 SSE 和持久化，模型只收到最小摘要；Electron 前端用独立视图模型渲染增强或旧版卡片。实现按 `order → Agent → frontend` 顺序推进，每个仓库先写失败测试，再做最小生产实现。

**Tech Stack:** Java 11、Spring Boot 2.4、MyBatis、JUnit 5、Mockito、Java 21、Spring Boot 3.5、Spring AI 1.1.8、OpenFeign、AssertJ、Electron、Vue 3、TypeScript、Vitest。

---

## 执行保护

执行每项任务前运行：

```powershell
git -C D:\GitCode\order status --short
git -C D:\GitCode\order-logistics-agent-server status --short
git -C D:\GitCode\order-logistics-agent-web status --short
```

必须避开 `order` 现有 Feign、`stable`、Postman 和测试改动，以及 Agent 的 `V8__create_agent_message_result.sql` 改动。新增或修改的生产文件要暂存；测试文件依照当前变更护栏保持未暂存。提交统一使用任务中列出的精确生产文件路径执行 `git commit --only`，不能捎带用户改动。本功能不增加数据库迁移和 Nacos 配置。

`order` 测试使用 `C:\Users\shwfo\.jdks\jbr-17.0.14` 执行 Maven；Agent 使用 `C:\Users\shwfo\.jdks\corretto-21.0.6`。

## 文件边界

`order` 新建：

- `AgentOrderAmountResponse.java`、`AgentOrderRecipientResponse.java`、`AgentOrderShipmentResponse.java`。
- `AgentOrderMoneyRow.java`、`AgentOrderGoodsStatsRow.java`、`AgentOrderShipmentStatsRow.java`。
- `AgentOrderAmountAssembler.java`、`AgentOrderRecipientResolver.java`、`AgentOrderCardAssembler.java`。
- 三个对应 Service 单元测试。

`order` 修改：

- `AgentOrderItemResponse.java`、`AgentOrderGoodsResponse.java`。
- `AgentOrderRow.java`、`AgentOrderGoodsRow.java`、`AgentOrderShipmentRow.java`。
- `AgentOrderQueryMapper.java`、`AgentOrderMaskingService.java`、`AgentOrderQueryService.java`。
- 现有 Mapper、QueryService 和 Controller 测试。

Agent 新建 `OrderAmount.java`、`OrderRecipient.java`、`OrderShipmentSummary.java`；修改 `OrderCard.java`、`OrderGoodsSummary.java`、`OrderSearchClient.java`、`OrderServiceGateway.java` 及对应测试。

前端新建 `order-card-view.ts` 和测试；修改 `chat.ts`、`OrderListCard.vue`、`OrderListCard.test.ts`、`chat-accumulator.test.ts`。

---

### Task 1: 增加 order 的有界数据库投影

**Files:**
- Create: `D:/GitCode/order/src/main/java/com/xjjk/ec/oms/agent/dao/AgentOrderMoneyRow.java`
- Create: `D:/GitCode/order/src/main/java/com/xjjk/ec/oms/agent/dao/AgentOrderGoodsStatsRow.java`
- Create: `D:/GitCode/order/src/main/java/com/xjjk/ec/oms/agent/dao/AgentOrderShipmentStatsRow.java`
- Modify: `D:/GitCode/order/src/main/java/com/xjjk/ec/oms/agent/dao/AgentOrderRow.java`
- Modify: `D:/GitCode/order/src/main/java/com/xjjk/ec/oms/agent/dao/AgentOrderGoodsRow.java`
- Modify: `D:/GitCode/order/src/main/java/com/xjjk/ec/oms/agent/dao/AgentOrderShipmentRow.java`
- Modify: `D:/GitCode/order/src/main/java/com/xjjk/ec/oms/agent/dao/AgentOrderQueryMapper.java`
- Test: `D:/GitCode/order/src/test/java/com/xjjk/ec/oms/agent/dao/AgentOrderQueryMapperTest.java`

- [ ] **Step 1: 写失败测试**

向 `AgentOrderQueryMapperTest` 增加：

```java
@Test
void richQueriesAreBatchScopedAndBounded() {
    Map<String, Object> p = new HashMap<>();
    p.put("orderIds", Arrays.asList(1L, 2L));
    p.put("limitPerOrder", 21);

    assertTrue(sql("findLatestMoneyByOrderIds", p).contains("MAX(Id)"));
    assertTrue(sql("findGoodsStatsByOrderIds", p).contains("COUNT(*) AS lineCount"));
    assertTrue(sql("findGoodsStatsByOrderIds", p)
            .contains("SUM(COALESCE(SumQuantity, Quantity))"));
    assertTrue(sql("findBoundedGoodsByOrderIds", p).contains("UNION ALL"));
    assertTrue(sql("findBoundedGoodsByOrderIds", p).contains("LIMIT ?"));
    assertTrue(sql("findShipmentStatsByOrderIds", p)
            .contains("COUNT(DISTINCT LogisticsCode)"));
    assertTrue(sql("findBoundedShipmentsByOrderIds", p).contains("w.BatchId = b.Id"));
}

@Test
void baseProjectionNeverSelectsDetailedAddress() {
    String value = sql("findByOrderCode",
            AgentOrderQueryParam.self("O-1", 10567L, 6));
    assertTrue(value.contains("ReceiverTelephoneId"));
    assertTrue(value.contains("ProvinceName"));
    assertTrue(value.contains("PaymentMethod"));
    assertFalse(value.contains("StreetName"));
    assertFalse(value.contains("DetailAddress"));
}
```

- [ ] **Step 2: 运行测试确认失败**

```powershell
cd D:\GitCode\order
$env:JAVA_HOME='C:\Users\shwfo\.jdks\jbr-17.0.14'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -Dtest=AgentOrderQueryMapperTest test
```

Expected: FAIL，新方法不存在或 SQL 断言不成立。

- [ ] **Step 3: 创建行模型并扩展现有模型**

```java
@Data
public class AgentOrderMoneyRow {
    private Long orderId;
    private Long totalAmount;
    private Long manualDiscount;
    private Long vipDiscount;
    private Long useCoupon;
    private Long useRebate;
    private Long subsidyAmount;
    private Long redPackageAmount;
    private Long useBalance;
    private Long freight;
    private Long payAmount;
}

@Data
public class AgentOrderGoodsStatsRow {
    private Long orderId;
    private Long lineCount;
    private Long totalQuantity;
}

@Data
public class AgentOrderShipmentStatsRow {
    private Long orderId;
    private Long shipmentCount;
}
```

`AgentOrderRow` 增加 `paymentMethod/receiverTelephoneId/provinceName/cityName/districtName`；`AgentOrderGoodsRow` 增加 `unitPrice/subtotal/gift`；`AgentOrderShipmentRow` 增加 `deliveryTime`。

- [ ] **Step 4: 增加 Mapper**

所有订单入口与 `findBaseByOrderIds` 使用相同的最新有效扩展关联：

```sql
LEFT JOIN order_extend e
  ON e.Id = (
      SELECT MAX(e2.Id)
      FROM order_extend e2
      WHERE e2.OrderId = o.Id AND e2.IsDeleted = 0
  )
```

只投影 `ReceiverTelephoneId/ProvinceName/CityName/DistrictName`，禁止选择街道和详细地址。新增方法签名：

```java
List<AgentOrderMoneyRow> findLatestMoneyByOrderIds(
        @Param("orderIds") Collection<Long> orderIds);
List<AgentOrderGoodsStatsRow> findGoodsStatsByOrderIds(
        @Param("orderIds") Collection<Long> orderIds);
List<AgentOrderGoodsRow> findBoundedGoodsByOrderIds(
        @Param("orderIds") Collection<Long> orderIds,
        @Param("limitPerOrder") int limitPerOrder);
List<AgentOrderShipmentStatsRow> findShipmentStatsByOrderIds(
        @Param("orderIds") Collection<Long> orderIds);
List<AgentOrderShipmentRow> findBoundedShipmentsByOrderIds(
        @Param("orderIds") Collection<Long> orderIds,
        @Param("limitPerOrder") int limitPerOrder);
```

金额 SQL 通过按 `OrderId` 分组的 `MAX(Id)` 关联最新有效 `order_money`。商品统计返回 `COUNT(*)` 与 `SUM(COALESCE(SumQuantity,Quantity))`。商品明细用一个动态 `UNION ALL`，每个最多 5 个授权订单各 `LIMIT 21`，选择 `PayPrice`、`COALESCE(SumAmount,SubtotalAmount)` 和 `IsGift`。包裹统计使用非空运单号的 `COUNT(DISTINCT LogisticsCode)`；明细同样单语句每单 `LIMIT 11`，经 `w.BatchId=b.Id` 获取 `DeliveryTime`，按首次运单行稳定排序。

两个明细方法必须在 SQL 层有界，不能先无界加载再在 Java 截断：

```java
@Select({
    "<script>",
    "<foreach collection='orderIds' item='orderId' separator=' UNION ALL '>",
    "(SELECT d.OrderId AS orderId,g.Name AS goodsName,d.SkuCode AS skuCode,",
    "s.GoodsModel AS goodsModel,d.PayPrice AS unitPrice,",
    "COALESCE(d.SumQuantity,d.Quantity) AS quantity,",
    "COALESCE(d.SumAmount,d.SubtotalAmount) AS subtotal,d.IsGift AS gift",
    "FROM order_goods_detail d",
    "LEFT JOIN goods g ON g.Id=d.GoodsId AND g.IsDeleted=0",
    "LEFT JOIN goods_sku s ON s.SkuCode=d.SkuCode",
    " AND s.GoodsId=d.GoodsId AND s.IsDeleted=0",
    "WHERE d.IsDeleted=0 AND d.OrderId=#{orderId}",
    "ORDER BY d.Id ASC LIMIT #{limitPerOrder})",
    "</foreach>",
    "</script>"
})
List<AgentOrderGoodsRow> findBoundedGoodsByOrderIds(
        @Param("orderIds") Collection<Long> orderIds,
        @Param("limitPerOrder") int limitPerOrder);

@Select({
    "<script>",
    "<foreach collection='orderIds' item='orderId' separator=' UNION ALL '>",
    "(SELECT w.OrderId AS orderId,w.LogisticsCode AS logisticsCode,",
    "MIN(w.CarrierId) AS carrierId,MIN(c.Name) AS carrierName,",
    "MIN(b.DeliveryTime) AS deliveryTime",
    "FROM order_delivery_waybill_code w",
    "LEFT JOIN order_delivery_batch b ON w.BatchId=b.Id AND b.IsDeleted=0",
    "LEFT JOIN data_carrier c ON c.Id=w.CarrierId AND c.IsDeleted=0",
    "WHERE w.IsDeleted=0 AND w.OrderId=#{orderId}",
    " AND w.LogisticsCode IS NOT NULL AND w.LogisticsCode&lt;&gt;''",
    "GROUP BY w.OrderId,w.LogisticsCode ORDER BY MIN(w.Id) ASC",
    "LIMIT #{limitPerOrder})",
    "</foreach>",
    "</script>"
})
List<AgentOrderShipmentRow> findBoundedShipmentsByOrderIds(
        @Param("orderIds") Collection<Long> orderIds,
        @Param("limitPerOrder") int limitPerOrder);
```

- [ ] **Step 5: 运行测试并提交生产代码**

Run: `mvn -Dtest=AgentOrderQueryMapperTest test`

Expected: PASS，既有三种订单入口仍包含权限分支。

```powershell
git add src/main/java/com/xjjk/ec/oms/agent/dao/AgentOrderMoneyRow.java src/main/java/com/xjjk/ec/oms/agent/dao/AgentOrderGoodsStatsRow.java src/main/java/com/xjjk/ec/oms/agent/dao/AgentOrderShipmentStatsRow.java src/main/java/com/xjjk/ec/oms/agent/dao/AgentOrderRow.java src/main/java/com/xjjk/ec/oms/agent/dao/AgentOrderGoodsRow.java src/main/java/com/xjjk/ec/oms/agent/dao/AgentOrderShipmentRow.java src/main/java/com/xjjk/ec/oms/agent/dao/AgentOrderQueryMapper.java
git commit --only -m "feat: add bounded rich order projections" -- src/main/java/com/xjjk/ec/oms/agent/dao/AgentOrderMoneyRow.java src/main/java/com/xjjk/ec/oms/agent/dao/AgentOrderGoodsStatsRow.java src/main/java/com/xjjk/ec/oms/agent/dao/AgentOrderShipmentStatsRow.java src/main/java/com/xjjk/ec/oms/agent/dao/AgentOrderRow.java src/main/java/com/xjjk/ec/oms/agent/dao/AgentOrderGoodsRow.java src/main/java/com/xjjk/ec/oms/agent/dao/AgentOrderShipmentRow.java src/main/java/com/xjjk/ec/oms/agent/dao/AgentOrderQueryMapper.java
```

---

### Task 2: 建立金额与收货信息边界

**Files:**
- Create: `D:/GitCode/order/src/main/java/com/xjjk/ec/oms/agent/api/AgentOrderAmountResponse.java`
- Create: `D:/GitCode/order/src/main/java/com/xjjk/ec/oms/agent/api/AgentOrderRecipientResponse.java`
- Create: `D:/GitCode/order/src/main/java/com/xjjk/ec/oms/agent/service/AgentOrderAmountAssembler.java`
- Create: `D:/GitCode/order/src/main/java/com/xjjk/ec/oms/agent/service/AgentOrderRecipientResolver.java`
- Modify: `D:/GitCode/order/src/main/java/com/xjjk/ec/oms/agent/service/AgentOrderMaskingService.java`
- Test: `D:/GitCode/order/src/test/java/com/xjjk/ec/oms/agent/service/AgentOrderAmountAssemblerTest.java`
- Test: `D:/GitCode/order/src/test/java/com/xjjk/ec/oms/agent/service/AgentOrderRecipientResolverTest.java`

- [ ] **Step 1: 写金额失败测试**

```java
@Test
void assemblesNamedFieldsWithoutBackCalculatingDiscount() {
    AgentOrderMoneyRow row = money(6000L, 100L, 200L, 300L, 400L,
            500L, 600L, 700L, 800L, 900L);
    AgentOrderAmountResponse result =
            new AgentOrderAmountAssembler().assemble(row);

    assertEquals(6000L, result.getGoodsTotalInFen());
    assertEquals(2100L, result.getDiscountInFen());
    assertEquals(700L, result.getBalanceDeductionInFen());
    assertEquals(800L, result.getFreightInFen());
    assertEquals(900L, result.getReceivableInFen());
}

@Test
void rejectsNegativeAndOverflowingMoney() {
    assertThrows(IllegalStateException.class,
            () -> new AgentOrderAmountAssembler().assemble(negativeMoney()));
    assertThrows(ArithmeticException.class,
            () -> new AgentOrderAmountAssembler().assemble(overflowingDiscount()));
}
```

- [ ] **Step 2: 写收货信息失败测试**

```java
@Test
void resolvesTwoMaskedPhonesAndOnlyProvinceCityDistrict() {
    AgentOrderRow row = order(1L, "石海文", "12,13,14",
            "湖南省", "常德市", "鼎城区");
    when(customerService.getEncodePhoneNum(List.of(12, 13)))
            .thenReturn(Map.of(12, "13812345678",
                    13, "07361234567"));

    AgentOrderRecipientResponse result =
            resolver.resolve(List.of(row)).get(1L);

    assertEquals("石**", result.getNameMasked());
    assertEquals("138****5678、073******67", result.getPhoneMasked());
    assertEquals("湖南省 常德市 鼎城区", result.getRegionText());
    assertFalse(result.toString().contains("12,13,14"));
}

@Test
void phoneFailureDegradesOnlyPhone() {
    when(customerService.getEncodePhoneNum(List.of(12)))
            .thenThrow(new IllegalStateException("downstream"));
    AgentOrderRecipientResponse result =
            resolver.resolve(List.of(orderWithPhone(12))).get(1L);
    assertNull(result.getPhoneMasked());
    assertEquals("石**", result.getNameMasked());
}
```

- [ ] **Step 3: 运行测试确认失败**

Run: `mvn -Dtest=AgentOrderAmountAssemblerTest,AgentOrderRecipientResolverTest test`

Expected: FAIL，新类型不存在。

- [ ] **Step 4: 实现金额口径**

```java
@Value
public class AgentOrderAmountResponse {
    long goodsTotalInFen;
    long discountInFen;
    long balanceDeductionInFen;
    long freightInFen;
    long receivableInFen;
}

@Service
public class AgentOrderAmountAssembler {
    public AgentOrderAmountResponse assemble(AgentOrderMoneyRow row) {
        if (row == null) return null;
        long discount = Math.addExact(
                Math.addExact(nonNegative(row.getManualDiscount()),
                        nonNegative(row.getVipDiscount())),
                Math.addExact(
                        Math.addExact(nonNegative(row.getUseCoupon()),
                                nonNegative(row.getUseRebate())),
                        Math.addExact(nonNegative(row.getSubsidyAmount()),
                                nonNegative(row.getRedPackageAmount()))));
        return new AgentOrderAmountResponse(
                nonNegative(row.getTotalAmount()), discount,
                nonNegative(row.getUseBalance()),
                nonNegative(row.getFreight()),
                nonNegative(row.getPayAmount()));
    }

    private long nonNegative(Long value) {
        long result = value == null ? 0L : value;
        if (result < 0) throw new IllegalStateException("订单金额数据不合法");
        return result;
    }
}
```

- [ ] **Step 5: 实现批量收货信息解析**

```java
@Value
public class AgentOrderRecipientResponse {
    String nameMasked;
    String phoneMasked;
    String regionText;
}
```

`AgentOrderRecipientResolver.resolve(List<AgentOrderRow>)` 先生成姓名和地区，解析正整数电话引用，每单只保留前两个引用，再在全批次去重后调用一次 `CustomerService.getEncodePhoneNum`。每单最多输出两个号码并按原引用顺序用 `、` 连接。下游异常只记录 `orderCount/phoneIdCount` 并让电话为空。

`AgentOrderMaskingService` 使用：

```java
public String maskCustomerName(String raw) {
    if (!hasText(raw)) return null;
    String value = raw.trim();
    int firstEnd = Character.offsetByCodePoints(value, 0, 1);
    int rest = value.codePointCount(firstEnd, value.length());
    return rest == 0 ? value : value.substring(0, firstEnd) + "*".repeat(rest);
}

public String maskPhone(String raw) {
    if (!hasText(raw)) return null;
    String value = raw.trim();
    if (value.length() > 32) return null;
    String digits = value.replaceAll("\\D", "");
    if (value.indexOf('*') >= 0 && digits.length() <= 7) return value;
    if (value.matches("1\\d{10}")) {
        return value.substring(0, 3) + "****" + value.substring(7);
    }
    if (digits.length() < 7) return null;
    return digits.substring(0, 3)
            + "*".repeat(digits.length() - 5)
            + digits.substring(digits.length() - 2);
}
```

- [ ] **Step 6: 运行测试并提交**

Run: `mvn -Dtest=AgentOrderAmountAssemblerTest,AgentOrderRecipientResolverTest test`

Expected: PASS。

```powershell
git add src/main/java/com/xjjk/ec/oms/agent/api/AgentOrderAmountResponse.java src/main/java/com/xjjk/ec/oms/agent/api/AgentOrderRecipientResponse.java src/main/java/com/xjjk/ec/oms/agent/service/AgentOrderAmountAssembler.java src/main/java/com/xjjk/ec/oms/agent/service/AgentOrderRecipientResolver.java src/main/java/com/xjjk/ec/oms/agent/service/AgentOrderMaskingService.java
git commit --only -m "feat: assemble safe order money and recipient data" -- src/main/java/com/xjjk/ec/oms/agent/api/AgentOrderAmountResponse.java src/main/java/com/xjjk/ec/oms/agent/api/AgentOrderRecipientResponse.java src/main/java/com/xjjk/ec/oms/agent/service/AgentOrderAmountAssembler.java src/main/java/com/xjjk/ec/oms/agent/service/AgentOrderRecipientResolver.java src/main/java/com/xjjk/ec/oms/agent/service/AgentOrderMaskingService.java
```

---

### Task 3: 组装 order 增强卡片

**Files:**
- Create: `D:/GitCode/order/src/main/java/com/xjjk/ec/oms/agent/api/AgentOrderShipmentResponse.java`
- Create: `D:/GitCode/order/src/main/java/com/xjjk/ec/oms/agent/service/AgentOrderCardAssembler.java`
- Modify: `D:/GitCode/order/src/main/java/com/xjjk/ec/oms/agent/api/AgentOrderGoodsResponse.java`
- Modify: `D:/GitCode/order/src/main/java/com/xjjk/ec/oms/agent/api/AgentOrderItemResponse.java`
- Modify: `D:/GitCode/order/src/main/java/com/xjjk/ec/oms/agent/service/AgentOrderQueryService.java`
- Test: `D:/GitCode/order/src/test/java/com/xjjk/ec/oms/agent/service/AgentOrderCardAssemblerTest.java`
- Test: `D:/GitCode/order/src/test/java/com/xjjk/ec/oms/agent/service/AgentOrderQueryServiceTest.java`
- Test: `D:/GitCode/order/src/test/java/com/xjjk/ec/oms/agent/api/AgentOrderControllerTest.java`

- [ ] **Step 1: 写卡片失败测试**

```java
@Test
void createsRichCardAndLegacyAliases() {
    AgentOrderItemResponse card = assembler.assemble(
            orderRow(), moneyRow(), goodsStats(21L, 25L), goodsRows(21),
            shipmentStats(11L), shipmentRows(11), recipient());

    assertEquals(194L, card.getPaymentMethodCode());
    assertEquals("款到发货", card.getPaymentMethodText());
    assertEquals(6000L, card.getAmount().getGoodsTotalInFen());
    assertEquals(25, card.getGoodsTotalCount());
    assertEquals(21, card.getGoodsLineCount());
    assertTrue(card.isGoodsTruncated());
    assertEquals(20, card.getGoods().size());
    assertEquals(11, card.getShipmentCount());
    assertTrue(card.isShipmentsTruncated());
    assertEquals(10, card.getShipments().size());
    assertEquals(card.getRecipient().getNameMasked(),
            card.getCustomerDisplayName());
    assertEquals(card.getAmount().getReceivableInFen(),
            card.getPayAmountInFen());
}
```

还要覆盖金额缺失、合法零值、未知收款方式、零价格非赠品、多包裹顺序和整数溢出。

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -Dtest=AgentOrderCardAssemblerTest,AgentOrderQueryServiceTest,AgentOrderControllerTest test`

Expected: FAIL，现有 DTO 只支持简化卡片。

- [ ] **Step 3: 扩展 API DTO**

```java
@Value
public class AgentOrderGoodsResponse {
    String goodsName;
    String skuCode;
    String specification;
    long unitPriceInFen;
    int quantity;
    long subtotalInFen;
    boolean gift;
}

@Value
public class AgentOrderShipmentResponse {
    String carrierName;
    String logisticsCode;
    String deliveryTime;
}
```

`AgentOrderItemResponse` 保留旧字段并增加：

```java
Long paymentMethodCode;
String paymentMethodText;
AgentOrderAmountResponse amount;
AgentOrderRecipientResponse recipient;
int goodsLineCount;
boolean goodsTruncated;
int shipmentCount;
boolean shipmentsTruncated;
List<AgentOrderShipmentResponse> shipments;
```

- [ ] **Step 4: 实现卡片组装器**

商品只取前 20 条，包裹只取前 10 条。商品名称缺失时显示“商品信息缺失”，数量必须正数，单价和小计必须非负；`gift` 只读取 `IsGift=1`。统计数使用 `Math.toIntExact`，截断标志由“总数大于返回数”计算。收款方式：

```java
Long paymentMethod = order.getPaymentMethod();
String paymentMethodText = PayMethodConst.getValueById(paymentMethod);
```

核心上限、截断和兼容字段派生写成显式代码：

```java
int goodsLineCount = Math.toIntExact(goodsStats == null
        ? 0L : goodsStats.getLineCount());
int goodsTotalCount = Math.toIntExact(goodsStats == null
        ? 0L : goodsStats.getTotalQuantity());
List<AgentOrderGoodsResponse> goods = rawGoods.stream()
        .limit(20).map(this::toGoodsResponse).collect(Collectors.toList());

int shipmentCount = Math.toIntExact(shipmentStats == null
        ? 0L : shipmentStats.getShipmentCount());
List<AgentOrderShipmentResponse> shipments = rawShipments.stream()
        .limit(10).map(this::toShipmentResponse).collect(Collectors.toList());

AgentOrderAmountResponse amount = amountAssembler.assemble(money);
long legacyPayAmount = amount == null ? 0L : amount.getReceivableInFen();
String legacyCarrier = shipments.isEmpty()
        ? "" : shipments.get(0).getCarrierName();
List<String> legacyCodes = shipments.stream()
        .map(AgentOrderShipmentResponse::getLogisticsCode)
        .collect(Collectors.toList());
boolean goodsTruncated = goodsLineCount > goods.size();
boolean shipmentsTruncated = shipmentCount > shipments.size();
```

旧字段只能由新结构派生：旧姓名等于 `recipient.nameMasked`，旧金额等于 `amount.receivableInFen` 或缺失时 0，旧承运商取首个包裹，旧运单数组由包裹生成。

- [ ] **Step 5: 收窄 QueryService 为批量编排**

注入 `AgentOrderRecipientResolver` 和 `AgentOrderCardAssembler`，选出最多 5 个授权订单后执行：

```java
List<AgentOrderMoneyRow> money =
        mapper.findLatestMoneyByOrderIds(orderIds);
List<AgentOrderGoodsStatsRow> goodsStats =
        mapper.findGoodsStatsByOrderIds(orderIds);
List<AgentOrderGoodsRow> goods =
        mapper.findBoundedGoodsByOrderIds(orderIds, 21);
List<AgentOrderShipmentStatsRow> shipmentStats =
        mapper.findShipmentStatsByOrderIds(orderIds);
List<AgentOrderShipmentRow> shipments =
        mapper.findBoundedShipmentsByOrderIds(orderIds, 11);
Map<Long, AgentOrderRecipientResponse> recipients =
        recipientResolver.resolve(selectedRows);
```

Mapper 返回 `null` 或数据库异常都使查询失败；真实记录缺失才映射为空。删除 QueryService 内旧的姓名、商品截断和运单合并代码。

- [ ] **Step 6: 运行 order Agent 回归**

```powershell
mvn -Dtest=AgentOrderQueryMapperTest,AgentOrderAmountAssemblerTest,AgentOrderRecipientResolverTest,AgentOrderCardAssemblerTest,AgentOrderQueryServiceTest,AgentOrderControllerTest,AgentOrderAccessServiceTest,AgentOrderLogisticsServiceTest,AgentOrderPartialDegradationTest,AgentOrderAuthInterceptorTest test
```

Expected: PASS，失败数和错误数为 0。

- [ ] **Step 7: 提交生产代码**

```powershell
git add src/main/java/com/xjjk/ec/oms/agent/api/AgentOrderItemResponse.java src/main/java/com/xjjk/ec/oms/agent/api/AgentOrderGoodsResponse.java src/main/java/com/xjjk/ec/oms/agent/api/AgentOrderShipmentResponse.java src/main/java/com/xjjk/ec/oms/agent/service/AgentOrderQueryService.java src/main/java/com/xjjk/ec/oms/agent/service/AgentOrderCardAssembler.java
git commit --only -m "feat: return production rich order cards" -- src/main/java/com/xjjk/ec/oms/agent/api/AgentOrderItemResponse.java src/main/java/com/xjjk/ec/oms/agent/api/AgentOrderGoodsResponse.java src/main/java/com/xjjk/ec/oms/agent/api/AgentOrderShipmentResponse.java src/main/java/com/xjjk/ec/oms/agent/service/AgentOrderQueryService.java src/main/java/com/xjjk/ec/oms/agent/service/AgentOrderCardAssembler.java
```

---

### Task 4: 扩展 Agent 领域与 Feign 契约

**Files:**
- Create: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/order/domain/OrderAmount.java`
- Create: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/order/domain/OrderRecipient.java`
- Create: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/order/domain/OrderShipmentSummary.java`
- Modify: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/order/domain/OrderCard.java`
- Modify: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/order/domain/OrderGoodsSummary.java`
- Modify: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/order/client/OrderSearchClient.java`
- Test: `D:/GitCode/order-logistics-agent-server/src/test/java/com/xjjk/agent/order/client/OrderServiceGatewayTest.java`

- [ ] **Step 1: 写富卡片和旧卡片夹具，运行编译失败测试**

富夹具必须包含：`paymentMethodCode/Text`、五项金额、脱敏收货信息、商品行数与截断标志、商品单价/小计/赠品、包裹总数与截断标志、包裹数组；旧夹具让全部新增字段为空。

```java
new OrderSearchClient.OrderCardData(
        "O123", "OUT123", 80, "在途", "2026-09-07 12:00:00",
        "石**", 0L, 6,
        194L, "款到发货",
        new OrderSearchClient.OrderAmountData(6000L, 0L, 6000L, 0L, 0L),
        new OrderSearchClient.OrderRecipientData(
                "石**", "138****1234", "湖南省 常德市 鼎城区"),
        1, false,
        List.of(new OrderSearchClient.OrderGoodsData(
                "商品名称", "SKU001", "50g/袋", 1000L, 6, 6000L, false)),
        "德邦", List.of("MASKED-WAYBILL"),
        1, false,
        List.of(new OrderSearchClient.OrderShipmentData(
                "德邦", "MASKED-WAYBILL", "2026-09-07 12:10:00")))
```

Run: `.\mvnw.cmd -Dtest=OrderServiceGatewayTest,OrderQueryToolsTest test`

Expected: FAIL，record 参数和新增类型不存在。

- [ ] **Step 2: 创建领域记录**

```java
public record OrderAmount(long goodsTotalInFen, long discountInFen,
        long balanceDeductionInFen, long freightInFen,
        long receivableInFen) {}

public record OrderRecipient(String nameMasked, String phoneMasked,
        String regionText) {}

public record OrderShipmentSummary(String carrierName, String logisticsCode,
        String deliveryTime) {}
```

`OrderGoodsSummary` 改为：

```java
public record OrderGoodsSummary(String goodsName, String skuCode,
        String specification, Long unitPriceInFen, int quantity,
        Long subtotalInFen, Boolean gift) {}
```

包装类型允许旧响应缺少新增商品字段。

- [ ] **Step 3: 扩展 OrderCard 与 OrderSearchClient**

`OrderCard` 保留所有旧字段，新增：

```java
Long paymentMethodCode;
String paymentMethodText;
OrderAmount amount;
OrderRecipient recipient;
Integer goodsLineCount;
Boolean goodsTruncated;
Integer shipmentCount;
Boolean shipmentsTruncated;
List<OrderShipmentSummary> shipments;
```

规范构造器复制全部列表。`OrderSearchClient` 增加对应 `OrderAmountData`、`OrderRecipientData`、`OrderShipmentData` record；新增 Feign 字段都可空以解码旧 order 响应。

- [ ] **Step 4: 运行测试并提交契约**

Run: `.\mvnw.cmd -Dtest=OrderServiceGatewayTest,OrderQueryToolsTest test`

Expected: 只剩 Gateway 富契约映射断言失败，不再有编译错误。

```powershell
git add src/main/java/com/xjjk/agent/order/domain/OrderAmount.java src/main/java/com/xjjk/agent/order/domain/OrderRecipient.java src/main/java/com/xjjk/agent/order/domain/OrderShipmentSummary.java src/main/java/com/xjjk/agent/order/domain/OrderCard.java src/main/java/com/xjjk/agent/order/domain/OrderGoodsSummary.java src/main/java/com/xjjk/agent/order/client/OrderSearchClient.java
git commit --only -m "feat: model rich order card contract" -- src/main/java/com/xjjk/agent/order/domain/OrderAmount.java src/main/java/com/xjjk/agent/order/domain/OrderRecipient.java src/main/java/com/xjjk/agent/order/domain/OrderShipmentSummary.java src/main/java/com/xjjk/agent/order/domain/OrderCard.java src/main/java/com/xjjk/agent/order/domain/OrderGoodsSummary.java src/main/java/com/xjjk/agent/order/client/OrderSearchClient.java
```

---

### Task 5: 严格校验并发布 Agent 富卡片

**Files:**
- Modify: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/order/client/OrderServiceGateway.java`
- Test: `D:/GitCode/order-logistics-agent-server/src/test/java/com/xjjk/agent/order/client/OrderServiceGatewayTest.java`
- Test: `D:/GitCode/order-logistics-agent-server/src/test/java/com/xjjk/agent/order/tool/OrderQueryToolsTest.java`
- Test: `D:/GitCode/order-logistics-agent-server/src/test/java/com/xjjk/agent/chat/api/dto/ChatStreamBusinessResultContractTest.java`
- Test: `D:/GitCode/order-logistics-agent-server/src/test/java/com/xjjk/agent/chat/service/conversation/AgentMessageResultQueryServiceTest.java`

- [ ] **Step 1: 写非法契约失败测试**

```java
@Test
void rejectsInvalidRichCardWithoutRetry() {
    OrderSearchClient.OrderCardData invalid =
            richCard(amountWithDiscount(-1L), 1, false, 0, false, List.of());
    assertThatThrownBy(() -> search(invalid))
            .isInstanceOf(OrderServiceUnavailableException.class)
            .hasMessage("订单服务响应不可用");
    assertThat(searchAttempts).hasValue(1);
}
```

另测商品超过 20、包裹超过 10、空元素、空运单号、总数小于数组、未截断却总数不等、完整 11 位手机号和旧响应成功。

- [ ] **Step 2: 运行测试确认失败**

Run: `.\mvnw.cmd -Dtest=OrderServiceGatewayTest test`

Expected: FAIL，现有上限仍是 3 且没有富契约校验。

- [ ] **Step 3: 实现新旧双路径**

常量改为商品 20、卡片包裹 10。以下任一字段存在即进入富路径：

```java
private boolean isRich(OrderSearchClient.OrderCardData source) {
    return source.recipient() != null
            || source.goodsLineCount() != null
            || source.goodsTruncated() != null
            || source.shipmentCount() != null
            || source.shipmentsTruncated() != null
            || source.shipments() != null;
}
```

富路径要求 `recipient/goodsLineCount/goodsTruncated/shipmentCount/shipmentsTruncated/goods/shipments` 同时存在，`amount` 允许为空。校验：

```java
if (source.goodsLineCount() < source.goods().size()
        || (!source.goodsTruncated()
            && source.goodsLineCount() != source.goods().size())
        || source.shipmentCount() < source.shipments().size()
        || (!source.shipmentsTruncated()
            && source.shipmentCount() != source.shipments().size())) {
    throw invalidResponse();
}
```

金额非负；商品名非空、数量正数、可选单价/小计非负；包裹运单号非空。非空 `phoneMasked` 必须包含 `*`，且去掉非数字字符后最多保留 7 位数字，否则拒绝。旧路径保留原校验并把新字段置空，不能让契约错误触发重试。

- [ ] **Step 4: 锁定模型、SSE 和历史持久化**

`OrderQueryToolsTest` 使用包含收货人、金额、20 条商品和 10 个包裹的富卡片；断言发布对象完整，但模型文本不含姓名、电话、地区、SKU、金额拆分或运单号。

`ChatStreamBusinessResultContractTest` 断言 `schemaVersion=1` 且序列化 JSON 包含：

```java
assertThat(card.path("amount").path("goodsTotalInFen").asLong())
        .isEqualTo(6000L);
assertThat(card.path("recipient").path("nameMasked").asText())
        .isEqualTo("石**");
assertThat(card.path("goods").get(0).path("subtotalInFen").asLong())
        .isEqualTo(6000L);
assertThat(card.path("shipments").get(0).path("deliveryTime").asText())
        .isEqualTo("2026-09-07 12:10:00");
```

`AgentMessageResultQueryServiceTest` 增加富 `order-list/v1` JSON，断言历史接口原样返回嵌套字段且不查询订单服务。

- [ ] **Step 5: 运行测试并提交**

```powershell
.\mvnw.cmd -Dtest=OrderServiceGatewayTest,OrderQueryToolsTest,ChatStreamBusinessResultContractTest,AgentMessageResultQueryServiceTest test
```

Expected: PASS。

```powershell
git add src/main/java/com/xjjk/agent/order/client/OrderServiceGateway.java
git commit --only -m "feat: validate rich order card responses" -- src/main/java/com/xjjk/agent/order/client/OrderServiceGateway.java
```

Agent 的既有 V8 文件必须保持原状态。

---

### Task 6: 建立前端新旧视图模型

**Files:**
- Create: `D:/GitCode/order-logistics-agent-web/src/renderer/src/components/order-card-view.ts`
- Modify: `D:/GitCode/order-logistics-agent-web/src/renderer/src/contracts/chat.ts`
- Test: `D:/GitCode/order-logistics-agent-web/src/renderer/src/components/order-card-view.test.ts`
- Test: `D:/GitCode/order-logistics-agent-web/src/renderer/src/stores/chat-accumulator.test.ts`

- [ ] **Step 1: 写纯函数失败测试**

```ts
it('distinguishes missing money from valid zero money', () => {
  expect(moneyRows({ ...richOrder(), amount: null })).toEqual([])
  expect(moneyRows({ ...richOrder(), amount: zeroAmount() })
    .map((item) => item.value))
    .toEqual(['¥0.00', '¥0.00', '¥0.00', '¥0.00', '¥0.00'])
})

it('uses rich data and falls back for legacy history', () => {
  expect(isRichOrderCard(richOrder())).toBe(true)
  expect(isRichOrderCard(legacyOrder())).toBe(false)
  expect(recipientName(legacyOrder())).toBe('石**')
})

it('bounds visible goods', () => {
  expect(visibleGoods(richOrderWithGoods(25), false)).toHaveLength(3)
  expect(visibleGoods(richOrderWithGoods(25), true)).toHaveLength(20)
})
```

- [ ] **Step 2: 运行测试确认失败**

Run: `npm test -- --run src/renderer/src/components/order-card-view.test.ts`

Expected: FAIL，新文件和类型不存在。

- [ ] **Step 3: 扩展 TypeScript 契约**

创建 `OrderAmount`、`OrderRecipient`、`OrderShipmentSummary`。`OrderGoodsSummary` 增加可选 `unitPriceInFen/subtotalInFen/gift`。`OrderCard` 保留旧字段并增加可选的 `paymentMethodCode/Text`、`amount`、`recipient`、`goodsLineCount/goodsTruncated`、`shipmentCount/shipmentsTruncated` 和 `shipments`。

```ts
export interface OrderAmount {
  goodsTotalInFen: number
  discountInFen: number
  balanceDeductionInFen: number
  freightInFen: number
  receivableInFen: number
}

export interface OrderRecipient {
  nameMasked: string | null
  phoneMasked: string | null
  regionText: string | null
}

export interface OrderShipmentSummary {
  carrierName: string | null
  logisticsCode: string
  deliveryTime: string | null
}
```

- [ ] **Step 4: 实现纯视图函数**

```ts
export function formatAmount(valueInFen: number): string {
  return '¥' + (valueInFen / 100).toFixed(2)
}

export function formatOptionalAmount(valueInFen?: number | null): string {
  return valueInFen == null ? '未提供' : formatAmount(valueInFen)
}

export function isRichOrderCard(order: OrderCard): boolean {
  return order.recipient != null ||
    order.goodsLineCount != null ||
    order.shipmentCount != null
}

export function visibleGoods(order: OrderCard, expanded: boolean): OrderGoodsSummary[] {
  return order.goods.slice(0, expanded ? 20 : 3)
}

export function moneyRows(order: OrderCard): Array<{ label: string; value: string }> {
  if (!order.amount) return []
  return [
    ['商品合计', order.amount.goodsTotalInFen],
    ['优惠抵扣', order.amount.discountInFen],
    ['消费预存', order.amount.balanceDeductionInFen],
    ['运费', order.amount.freightInFen],
    ['应收金额', order.amount.receivableInFen]
  ].map(([label, value]) => ({
    label: String(label),
    value: formatAmount(Number(value))
  }))
}
```

同时实现 `recipientName` 和 `shipmentRows`。旧包裹由旧承运商与运单数组生成展示模型，发货时间保持空值，禁止虚构。

- [ ] **Step 5: 运行测试和类型检查**

为 `chat-accumulator.test.ts` 同时保留一条富 `v1` 和旧 `v1` 夹具。

```powershell
npm test -- --run src/renderer/src/components/order-card-view.test.ts src/renderer/src/stores/chat-accumulator.test.ts
npm run typecheck:web
```

Expected: 测试 PASS，类型检查退出码 0。

- [ ] **Step 6: 提交生产代码**

```powershell
git add src/renderer/src/contracts/chat.ts src/renderer/src/components/order-card-view.ts
git commit --only -m "feat: add rich order card view model" -- src/renderer/src/contracts/chat.ts src/renderer/src/components/order-card-view.ts
```

---

### Task 7: 实现增强订单卡片界面

**Files:**
- Modify: `D:/GitCode/order-logistics-agent-web/src/renderer/src/components/OrderListCard.vue`
- Test: `D:/GitCode/order-logistics-agent-web/src/renderer/src/components/OrderListCard.test.ts`

- [ ] **Step 1: 写结构和动作失败测试**

```ts
it('renders rich sections and bounded notices', () => {
  expect(source).toContain('金额信息')
  expect(source).toContain('收货信息')
  expect(source).toContain('商品明细')
  expect(source).toContain('发货包裹')
  expect(source).toContain('展开全部')
  expect(source).toContain('仅展示前 20 条')
  expect(source).toContain('仅展示前 10 个包裹')
  expect(source).not.toContain('实付金额')
  expect(source).not.toContain('v-html')
})

it('dispatches the clicked card instead of array item zero', () => {
  expect(source).toContain('queryOrderLogistics(order)')
  expect(source).toContain("orderCode: order.orderCode")
  expect(source).not.toContain('props.result.data.items[0]')
})
```

- [ ] **Step 2: 运行测试确认失败**

Run: `npm test -- --run src/renderer/src/components/OrderListCard.test.ts`

Expected: FAIL，当前组件仍读取旧字段和数组第一项。

- [ ] **Step 3: 实现独立展开状态与动作**

```ts
const expandedOrders = ref(new Set(
  props.result.data.items[0] ? [props.result.data.items[0].orderCode] : []
))
const expandedGoods = ref(new Set<string>())

function queryOrderLogistics(order: OrderCard): void {
  if (props.disabled) return
  emit('action', {
    type: 'QUERY_ORDER_LOGISTICS',
    orderCode: order.orderCode
  })
}
```

`toggleOrder` 与 `toggleGoods` 每次复制 Set 再更新；每张卡使用自身 `orderCode`。多订单首张展开，其余折叠。

- [ ] **Step 4: 渲染五个紧凑区域**

顺序为订单概览、金额、收货、商品、包裹。金额缺失显示“金额信息暂不可用”，合法零值显示 `¥0.00`。商品默认 3 条，最多展开 20 条；赠品只看显式标志。包裹每条展示承运商、运单号、发货时间。两个截断标志分别给出明确提示。

每张卡都能触发“查看物流”；“订单详情”继续禁用。模板不使用 `v-html`。内部采用两列网格，窄窗口变单列，不增加嵌套固定高度滚动条。

富卡片模板的关键数据绑定如下；旧卡片保留独立回退分支：

```vue
<section v-if="isRichOrderCard(order)" class="order-rich-content">
  <dl v-if="moneyRows(order).length" class="order-money-grid">
    <div v-for="item in moneyRows(order)" :key="item.label">
      <dt>{{ item.label }}</dt>
      <dd>{{ item.value }}</dd>
    </div>
  </dl>
  <p v-else class="business-empty">金额信息暂不可用</p>

  <section class="order-recipient">
    <h4>收货信息</h4>
    <span>{{ order.recipient?.nameMasked || '未提供' }}</span>
    <span>{{ order.recipient?.phoneMasked || '电话暂不可用' }}</span>
    <span>{{ order.recipient?.regionText || '地区未提供' }}</span>
  </section>

  <ul class="order-goods">
    <li v-for="goods in visibleGoods(order, expandedGoods.has(order.orderCode))"
        :key="goods.skuCode || goods.goodsName">
      <strong>{{ goods.goodsName }}</strong>
      <span>{{ goods.skuCode || 'SKU未提供' }}</span>
      <span>{{ goods.specification || '规格未提供' }}</span>
      <span>{{ formatOptionalAmount(goods.unitPriceInFen) }} × {{ goods.quantity }}</span>
      <span>{{ formatOptionalAmount(goods.subtotalInFen) }}</span>
      <b v-if="goods.gift">赠品</b>
    </li>
  </ul>
  <p v-if="order.goodsTruncated">仅展示前 20 条，还有更多商品</p>

  <ul class="order-shipments">
    <li v-for="shipment in shipmentRows(order)" :key="shipment.logisticsCode">
      <span>{{ shipment.carrierName || '承运商未提供' }}</span>
      <span>{{ shipment.logisticsCode }}</span>
      <span>{{ shipment.deliveryTime || '发货时间未提供' }}</span>
    </li>
  </ul>
  <p v-if="order.shipmentsTruncated">仅展示前 10 个包裹</p>
</section>
```

- [ ] **Step 5: 运行前端质量门禁**

```powershell
npm test -- --run src/renderer/src/components/OrderListCard.test.ts src/renderer/src/components/order-card-view.test.ts src/renderer/src/components/ChatWindow.test.ts
npm run typecheck
npm run lint
```

Expected: 全部退出码 0。

- [ ] **Step 6: 提交生产组件**

```powershell
git add src/renderer/src/components/OrderListCard.vue
git commit --only -m "feat: render production rich order cards" -- src/renderer/src/components/OrderListCard.vue
```

---

### Task 8: 全链路回归与真实数据验收

**Files:**
- Verify only: `D:/GitCode/order`
- Verify only: `D:/GitCode/order-logistics-agent-server`
- Verify only: `D:/GitCode/order-logistics-agent-web`
- Reference: `D:/GitCode/order-logistics-agent-server/docs/superpowers/specs/2026-09-07-rich-order-card-design.md`

- [ ] **Step 1: order 回归**

```powershell
cd D:\GitCode\order
$env:JAVA_HOME='C:\Users\shwfo\.jdks\jbr-17.0.14'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -Dtest=AgentOrderQueryMapperTest,AgentOrderAmountAssemblerTest,AgentOrderRecipientResolverTest,AgentOrderCardAssemblerTest,AgentOrderQueryServiceTest,AgentOrderControllerTest,AgentOrderAccessServiceTest,AgentOrderLogisticsServiceTest,AgentOrderPartialDegradationTest,AgentOrderAuthInterceptorTest test
```

Expected: 失败数和错误数均为 0。

- [ ] **Step 2: Agent 回归**

```powershell
cd D:\GitCode\order-logistics-agent-server
$env:JAVA_HOME='C:\Users\shwfo\.jdks\corretto-21.0.6'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd -Dtest=OrderServiceGatewayTest,OrderQueryToolsTest,ChatStreamBusinessResultContractTest,AgentMessageResultQueryServiceTest,ChatTurnStructuredResultFinishTest,RecentOrderReferenceProviderTest test
```

Expected: 失败数和错误数均为 0。

- [ ] **Step 3: 前端完整门禁**

```powershell
cd D:\GitCode\order-logistics-agent-web
npm test
npm run typecheck
npm run lint
npm run build
```

Expected: Vitest 全部 PASS，其他命令退出码 0。

- [ ] **Step 4: 直接接口核对**

按 `order → Agent → desktop` 启动。Token 只从环境变量注入，不写入命令、日志或文档。用已确认测试订单核对：状态、制单时间、款到发货、商品合计、消费预存、运费、应收、商品单价/数量/小计、脱敏姓名电话、省市区、承运商、运单和发货时间。

Expected: HTTP 200；一张卡；商品和包裹未截断；响应不含 `orderId/customerId/receiverTelephoneId/streetName/detailAddress`。

- [ ] **Step 5: 桌面端验收**

验证模型只给简短说明；卡片五区完整；零元应收显示 `¥0.00`；点击当前卡片查看物流得到正确时间线；重进会话后富卡片从 `agent_message_result` 恢复；旧卡片仍回退显示；日志无个人信息和完整 JSON。

- [ ] **Step 6: 状态和密钥检查**

```powershell
git -C D:\GitCode\order status --short
git -C D:\GitCode\order-logistics-agent-server status --short
git -C D:\GitCode\order-logistics-agent-web status --short
git -C D:\GitCode\order diff --check
git -C D:\GitCode\order-logistics-agent-server diff --check
git -C D:\GitCode\order-logistics-agent-web diff --check
```

Expected: 只剩执行前已知用户改动和按护栏未暂存的测试，没有意外生产改动或空白错误。

上线或共享环境前轮换已经暴露的内部服务 Token；确认旧 Token 返回 401、新 Token 查询成功后结束发布。
