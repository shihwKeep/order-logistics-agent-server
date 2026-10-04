# Customer TimeLimiter Configuration Design

## Problem

The customer search gateway binds `integration.customer.resilience.call-timeout`, but the running Spring Cloud CircuitBreaker still uses Resilience4j's default one-second `TimeLimiter`. As a result, a healthy local customer query that takes longer than one second is cancelled before the configured five-second boundary.

## Design

Keep Nacos as the source of the timeout value. When building the `customerSearch` circuit breaker, register a named `TimeLimiterConfig` in `TimeLimiterRegistry` using `integration.customer.resilience.call-timeout`, matching the existing order integration pattern. The factory customizer continues to receive the same configuration so both lookup paths are consistent.

No retry, Feign timeout, tool-routing, prompt, or downstream API behavior changes are included.

## Verification

Add a focused configuration test that starts with the registry default, registers the customer configuration, and asserts that the named `customerSearch` timeout equals the configured value and cancels the running future. Run the focused test first, then the complete Maven test suite.
