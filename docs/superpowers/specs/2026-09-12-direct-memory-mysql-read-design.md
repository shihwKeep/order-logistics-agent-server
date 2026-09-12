# Strict User-Memory Questions: MySQL-First Read Design

## Problem

Strict questions such as `我平时主要使用什么编程语言？` currently depend on semantic recall. After Knowledge Service or the embedding model restarts, the vector channel can exceed its three-second timeout. Elasticsearch may remain available but return no lexical hit, producing `KEYWORD_ONLY / NO_CANDIDATE`. The Agent then incorrectly renders this degraded empty result as “not remembered”, although the active memory already exists in MySQL.

## Decision

For the closed set of strict self-memory question types, MySQL is the primary and sufficient fact source. The deterministic answer path will read active memories by exact category and will not call Elasticsearch, Milvus, the embedding model, the reranker, or the chat model.

The alternatives were rejected:

- Increasing the embedding timeout or warming the model still couples a factual lookup to an expensive service and only reduces the probability of failure.
- Retrying semantic recall adds latency and cost, and still cannot prove that an empty result means the user has no saved memory.

## Data Flow

1. The existing strict classifier maps the user question to one `MemoryCategory`.
2. A MySQL recall method validates tenant ID, user ID, the master switch, and the current memory generation.
3. It loads only rows owned by that tenant and user with the current generation, exact category, `ACTIVE` status, and a non-expired lifetime.
4. Results are ordered with `USER_EXPLICIT` before `AUTO_EXTRACT`, then by confidence and recency, and are bounded by the existing maximum selected-memory setting.
5. The existing safe renderer canonicalizes each candidate. The first renderable value becomes the deterministic response.
6. An initialized, successful, empty category read means `NOT_REMEMBERED`. A missing settings row means `NOT_INITIALIZED`; a disabled switch means `DISABLED`; database or invalid-generation failures mean `UNAVAILABLE`.

## Safety and Compatibility

- MySQL remains the source of truth; no new table or migration is required.
- Every query is scoped by tenant, user, and memory generation. Content is never logged.
- Existing category canonicalization and output templates remain the only way memory text reaches the response.
- Explicit-memory commands, business routing, ordinary chat context, and general semantic memory recall are unchanged.
- The strict direct-answer metric keeps the current bounded tags and outcomes.

## Tests and Acceptance

- Reproduce the cold-vector condition with an active Java memory in MySQL and an unavailable semantic gateway; the first strict question must still answer Java and the gateway must not be called.
- Verify source priority, expiration filtering, ownership/generation isolation, disabled and uninitialized states, and database failure handling.
- Verify non-strict and business questions keep their existing paths.
- Run focused tests, the complete Agent test suite, and packaging.
- Manual acceptance: restart services and ask the same programming-language question ten times, including the first request after restart; all ten responses must contain Java.
