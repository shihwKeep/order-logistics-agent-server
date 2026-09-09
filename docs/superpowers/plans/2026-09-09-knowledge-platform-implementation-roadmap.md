# Knowledge Platform Delivery Roadmap

## Purpose

The approved design spans three repositories and several independently testable subsystems. Delivery is split into six plans so authentication, ingestion, retrieval and UI failures remain isolated.

Governing specification:

`docs/superpowers/specs/2026-09-09-order-logistics-knowledge-platform-design.md`

## Repository Ownership

| Repository | Responsibility |
|---|---|
| `D:\GitCode\order-logistics-knowledge-service` | Metadata, authorization, ingestion, publication, retrieval and citations |
| `D:\GitCode\order-logistics-agent-admin-web` | Browser knowledge-management console |
| `D:\GitCode\order-logistics-agent-server` | Knowledge tool orchestration and grounded answer generation |
| `D:\GitCode\order-logistics-agent-web` | Desktop citation cards and source preview |
| `D:\GitCode\gateway` | `/knowledge/**` route only; no knowledge-domain logic |

## Ordered Plans

### Plan 1: Knowledge Service Foundation

Deliver a runnable Java 21 service with SSPX login, Redis-backed HttpOnly admin sessions, role-code authorization, tenant isolation, Flyway foundation tables, knowledge-base CRUD and audit records.

Acceptance gate: system administrators manage only their own tenant; super administrators manage an explicitly selected tenant; ordinary users receive `403`; authentication dependency failure fails closed; MySQL and web authorization tests pass.

Detailed plan: `docs/superpowers/plans/2026-09-09-knowledge-service-foundation-implementation-plan.md`.

### Plan 2: Versioned Document Ingestion

Add MinIO source storage, document/version/unit/chunk/task/outbox tables, RabbitMQ wake-up, lease-based workers, format-specific parsers, independent PaddleOCR, normalized-text preview, human correction and resumable failures.

Acceptance gate: every approved format reaches preview; OCR failures are page-scoped and retryable; restart or lost RabbitMQ messages do not lose tasks; corrected text creates an immutable revision.

### Plan 3: Embedding, Retrieval and Publication

Add `qwen3-embedding:4b-q4_K_M`, a 2560-dimensional Milvus collection, Elasticsearch IK BM25, RRF, the existing BGE reranker, manual publication, rollback, final MySQL version validation and controlled degradation.

Acceptance gate: draft content never reaches online search; publication and rollback preserve availability; low-confidence retrieval returns `answerable=false`; all degradation paths match the design.

### Plan 4: Knowledge Admin Web

Create a Vue 3, TypeScript and Vite browser application with login, tenant-aware navigation, knowledge-base/document management, task progress, source/parsed preview, OCR correction, publication, rollback, retrieval diagnostics and audit views.

Acceptance gate: no secret or persisted bearer token in browser JavaScript; mutating requests use CSRF; super-admin tenant switching is explicit; component and browser flows pass.

### Plan 5: Agent and Desktop Integration

Add authenticated internal retrieval to Agent, strict evidence grounding, structured citation SSE results, and clickable citation cards in the Electron desktop app.

Acceptance gate: knowledge questions retrieve evidence; `answerable=false` cannot produce a business-policy conclusion; citations come only from Knowledge Service; existing business tools still pass regression tests.

### Plan 6: End-to-End Hardening

Add fault injection, resource limits, observability, Chinese retrieval evaluation, security regressions, backup/restore instructions and the local startup runbook.

Acceptance gate: every design acceptance criterion has a traceable check; ingestion cannot starve online retrieval; tenant-boundary and prompt-injection tests pass; operators can diagnose failures without sensitive content.

## Execution Rule

Complete and verify each plan before authoring or executing the next. Commit changes only in the repository that owns them, and preserve all pre-existing uncommitted user changes.
