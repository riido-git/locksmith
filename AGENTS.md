# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

## Project Overview

Locksmith is a Spring Boot starter library providing Redis-based distributed coordination primitives (locks, semaphores, rate limiters) via annotations and programmatic APIs. Built on Redisson, targeting Java 17+ and Spring Boot 4.0+.

## Build Commands

```bash
mvn clean compile                # Compile (also auto-formats code via fmt-maven-plugin)
mvn test                         # Run all tests (requires Docker for Testcontainers)
mvn clean package                # Build JAR
mvn clean install                # Install to local Maven repo
```

**CI-style test run** (excludes performance and virtual thread tests):
```bash
mvn -B test -Dexclude.performance.tests="**/*Performance*" -Dexclude.virtualthread.tests="**/VirtualThread*"
```

**Run a single test class:**
```bash
mvn test -Dtest=DistributedLockIntegrationTest
```

**Run a single test method:**
```bash
mvn test -Dtest=DistributedLockIntegrationTest#testMethodName
```

## Code Formatting

Google Java Style is enforced via `fmt-maven-plugin` (runs automatically during compile phase). No manual formatting step needed.

## Architecture

**Pattern:** Spring AOP aspects intercept annotated methods, acquire Redis-based coordination primitives via Redisson, execute the method, then release.

**Three coordination primitives**, each following the same layered pattern:

| Layer | Lock | Semaphore | Rate Limit |
|-------|------|-----------|------------|
| Annotation | `@DistributedLock` | `@DistributedSemaphore` | `@RateLimit` |
| Aspect | `DistributedLockAspect` | `DistributedSemaphoreAspect` | `RateLimitAspect` |
| Template | `LocksmithLockTemplate` | `LocksmithSemaphoreTemplate` | `LocksmithRateLimitTemplate` |
| Skip Handler | `LockSkipHandler` | `SemaphoreSkipHandler` | `RateLimitSkipHandler` |
| Metrics | `LockMetrics` | `SemaphoreMetrics` | `RateLimitMetrics` |
| Context | `LockContext` | `SemaphoreContext` | `RateLimitContext` |

**Key packages under `in.riido.locksmith`:**
- `aspect/` — AOP aspects that intercept annotations
- `autoconfigure/` — Spring Boot auto-configuration and properties
- `exception/` — Custom exceptions for each primitive
- `handler/` — Skip handler interfaces + built-in implementations (throw exception, return default)
- `metrics/` — Optional Micrometer integration
- `models/` — Context records passed to skip handlers
- `support/` — SpEL key resolution (`SpELKeyResolver`) and duration parsing (`DurationResolver`)
- `template/` — Programmatic APIs with builder pattern

**Handler resolution:** Aspects first check the Spring ApplicationContext for a matching bean, then fall back to reflective instantiation. Instances are cached in a `ConcurrentHashMap`.

**SpEL keys:** Expressions must be wrapped in `#{...}` (e.g., `#{#userId}`). Literal strings without `#{...}` are used as-is.

## Testing

- **Unit tests:** Handler and metrics classes in `src/test/java/.../handler/` and `metrics/`
- **Integration tests:** In `src/test/java/.../integration/` — require Docker (Testcontainers spins up Redis)
- Tests use `@Nested` classes, `@DisplayName`, and `DockerAvailableCondition` to skip when Docker is unavailable
- Performance tests (`*Performance*`) and virtual thread tests (`VirtualThread*`) are excluded in CI
- Coverage reports: `target/site/jacoco/`

## Key Design Details

- **Aspect ordering:** All aspects use `@Order(Ordered.HIGHEST_PRECEDENCE)` so locks/permits are acquired before transactions start.
- **Auto-configuration ordering:** `LocksmithMetricsAutoConfiguration` runs before `LocksmithAutoConfiguration` (`@AutoConfigureBefore`) so metrics beans are available for injection into aspects.
- **Null-safety:** The project uses `org.jspecify.annotations` (`@NonNull`, `@Nullable`) throughout. Follow this convention when adding code.
- **Duration format:** Annotation duration strings (leaseTime, waitTime, interval) support both simple (`"10s"`, `"5m"`) and ISO-8601 (`"PT10S"`) formats via `DurationResolver`.

## Configuration Properties

All under `locksmith.*` prefix — see `LocksmithProperties` record in `autoconfigure/`. Lock and semaphore have `enabled`, `lease-time`, `wait-time`, `key-prefix`, `debug`, and `metrics-enabled` properties. Rate limit has the same except no `lease-time`.

## Dependencies

Core dependencies (Spring AOP, Spring Context, AspectJ, Redisson, Spring Boot Autoconfigure) are `provided` scope — the consuming application supplies them. Micrometer is `optional`.

## Design Principles (Strictly Enforced by the User)

The user is meticulous about code quality and will reject sloppy work. Follow these principles without exception:

- **No code duplication** — never copy-paste logic across classes. If something exists already (e.g., handler resolution in aspects, SpEL key resolution), reuse it. Do not create separate utility methods that duplicate existing patterns.
- **Reusable logic belongs in `support/`** — generic operations like key resolution and duration parsing go in `support/` as `final` classes with private constructors. Never put reusable static methods inside aspect or template classes.
- **Design for extensibility** — the three primitives follow an identical layered pattern (annotation → aspect → template → skip handler → metrics → context). When adding a new primitive or feature, follow this same structure. Separate generic logic from primitive-specific logic.
- **Don't assume — ask** — when requirements are ambiguous or there are multiple valid approaches, ask the user before implementing. The user values being consulted over speed.
- **Push back when something is wrong** — if the user asks for something that violates software engineering principles, introduces code smells, breaks separation of concerns, or is otherwise a bad idea, say so bluntly. Do not silently comply. Explain why it's wrong and suggest the correct approach. The user respects honest technical disagreement and prefers being challenged over receiving bad code.
- **Always use existing helper/wrapper methods** — if a private helper exists for an operation (e.g., `getHandlerInstance()`, `formatMethodSignature()`), use it consistently everywhere in that class. Never bypass it by calling the underlying logic directly. Consistency is non-negotiable.
- **No magic strings** — never hardcode string literals that represent domain values. Always use enum values or constants. If the value exists in an enum (e.g., `AcquisitionMode`, `LockType`, `RateType`), reference the enum.
- **Data normalization at the source** — formatting, sanitization, and key resolution must happen at a single point (the aspect or template), never duplicated across consumers downstream. This prevents duplication and ensures consistency.
- **Records for truly immutable data only** — context objects (`LockContext`, `SemaphoreContext`, `RateLimitContext`) and properties (`LocksmithProperties`) are records because they are genuinely immutable. Do not use records for objects that need post-construction mutation.
- **Utility classes must be `final` with private constructor** — see `SpELKeyResolver` and `DurationResolver` as examples.

## Git Conventions

- **Never add `Co-Authored-By: Codex`** or any Codex co-author line to git commits unless explicitly told to do so.
