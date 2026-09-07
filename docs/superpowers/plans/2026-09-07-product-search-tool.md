# Product Search Tool Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let an authenticated operator ask for products and receive a SKU-level list containing name, specification, price, fixed-shop inventory, listing state, hover image data, and a placeholder detail action.

**Architecture:** `cxservice` owns a new read-only product-search facade over `cx.goods`, `cx.goods_sku`, `cx.goods_sku_extra`, and `cx.goods_stock_shop`. The Agent exposes one Spring AI `search_products` tool, injects identity and `shopId=10` outside model-controlled arguments, returns compact facts to the model, and emits the complete list through SSE. The Electron frontend renders the structured result as a compact table.

**Tech Stack:** Java 21, Spring MVC, MyBatis-Plus, Spring AI 1.1.8, OpenFeign, SSE, Vue 3, Pinia, Vitest.

---

### Task 1: cxservice product-search facade

**Files:**
- Create: `D:/GitCode/cxservice/src/main/java/com/xjjk/ec/originalintention/agentproduct/**`
- Create: `D:/GitCode/cxservice/src/test/java/com/xjjk/ec/originalintention/agentproduct/**`

- [x] Write failing tests for keyword normalization, page-size bounds, status mapping, inventory calculation, and endpoint contract.
- [x] Run the focused tests and confirm failure because the facade does not exist.
- [x] Add request/response records, mapper, service, and controller under one capability package.
- [x] Query SKU rows with `ShopId=10`, `IsDeleted=0`, and stable exact-before-fuzzy ordering.
- [x] Run focused and project compilation tests.

### Task 2: Agent product tool and SSE contract

**Files:**
- Create: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/product/**`
- Modify: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/chat/service/model/AiChatService.java`
- Modify: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/chat/service/stream/ChatTurnRunner.java`
- Modify: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/chat/api/dto/ChatStreamPayloads.java`
- Modify: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/chat/stream/ChatSseSession.java`
- Modify: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/chat/config/ChatContextProperties.java`
- Modify: `D:/GitCode/order-logistics-agent-server/src/main/java/com/xjjk/agent/chat/service/memory/ChatContextSelector.java`
- Create: `D:/GitCode/order-logistics-agent-server/src/test/java/com/xjjk/agent/product/**`

- [x] Write failing tests for model-controlled arguments, server-controlled shop/identity context, compact model result, and SSE product-result payload.
- [x] Register `search_products` per chat request with Spring AI `ToolContext`.
- [x] Add Feign integration and deterministic downstream error mapping.
- [x] Add explicit Tool Schema/result Token reserve to context selection.
- [x] Run focused tests and full Maven tests.

### Task 3: Electron product-list presentation

**Files:**
- Modify: `D:/GitCode/order-logistics-agent-web/src/renderer/src/contracts/chat.ts`
- Modify: `D:/GitCode/order-logistics-agent-web/src/renderer/src/stores/chat-accumulator.ts`
- Modify: `D:/GitCode/order-logistics-agent-web/src/renderer/src/stores/chat.ts`
- Modify: `D:/GitCode/order-logistics-agent-web/src/renderer/src/components/ChatWindow.vue`
- Modify: `D:/GitCode/order-logistics-agent-web/src/renderer/src/stores/chat-accumulator.test.ts`
- Modify: `D:/GitCode/order-logistics-agent-web/src/renderer/src/components/ChatWindow.test.ts`

- [x] Write failing tests for accumulation and rendering of product-list events.
- [x] Add typed `product-list` result payload.
- [x] Render name, SKU, specification, price, stock, listing state, image hover preview, and placeholder detail button.
- [x] Run unit tests, typecheck, lint, and production build.
- [x] Commit frontend feature separately from the baseline commit (`d3ac2b4`).

### Task 4: Cross-service verification

- [x] Compile and test `cxservice`.
- [x] Compile and test the Agent without modifying the user's unrelated staged work.
- [x] Verify frontend tests and package build.
- [x] Document required Nacos properties and a manual SSE verification request.
