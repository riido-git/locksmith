# Changelog

All notable changes to this project will be documented in this file.

## [4.0.0] - 2026-09-23

A ground-up rewrite. The public API, the properties, the metrics and the Redis key layout change;
nothing from 3.x is kept for compatibility. Read the migration table before upgrading.

### Added
- `LockOperations` and `SemaphoreOperations` beans: a builder per acquire that returns an
  `AutoCloseable` handle, plus `isLocked(key, type)` and `availablePermits(key)`. The annotations
  call the same code.
- `onFailure = THROW | SKIP | HANDLER` on both annotations, with `LockFailureHandler` and
  `SemaphoreFailureHandler` beans resolved by type.
- Startup validation: every annotation is checked when its bean is created, and a misconfiguration
  fails the application context refresh.
- Key templates with literal text and `#{...}` islands, for example `"user:#{#userId}"`.
- `LocksmithException` as the common unchecked base of every Locksmith exception, and
  `LocksmithConfigurationException` for misconfigurations.
- Two Micrometer timers, `locksmith.acquire` and `locksmith.held`, tagged by primitive and outcome.
- Async methods: a method that returns a `CompletionStage` holds its lock and permit until the
  returned future completes, normally, exceptionally or by cancellation. Reactive return types,
  Kotlin `suspend` functions and a plain `Future` without `@Async` fail startup.

### Removed
- Rate limiting (`@RateLimit` and everything around it).
- The AspectJ aspects and the `aspectjweaver` dependency; interception is a Spring AOP advisor.
- The templates with callbacks, the skip handlers and their built-in implementations,
  `AcquisitionMode`, `LeaseExpirationBehavior`, the lease-expired exceptions, and the per-primitive
  properties.

### Fixed
- A key template such as `order:#{#id}` whose `#{...}` island resolves to `null` or a blank string
  now throws `LocksmithConfigurationException`, instead of every such call sharing one key.
- After re-creating a lost semaphore, the retry waits only the part of `waitTime` that is left, not
  the full `waitTime` again.
- When another instance creates a semaphore first, the count is read again and changed if it
  differs, so the last writer wins as documented.
- The Redis calls that set a semaphore's count no longer run inside the permit-count cache's map
  operation, so they never block an acquire of another key.

### Migration from 3.x

One row per dropped or changed item. Class names without a package are in `in.riido.locksmith` or
the package named in the 4.0 column.

| 3.x | 4.0 | what to do |
|---|---|---|
| Spring Boot 4.0 or later | Spring Boot 4.1.x | Upgrade to Spring Boot 4.1.x. |
| `org.aspectj:aspectjweaver` required on the classpath | not used | Remove the dependency if nothing else needs it. |
| AspectJ aspects `aspect.DistributedLockAspect`, `DistributedSemaphoreAspect`, `RateLimitAspect` | Spring AOP advisor, internal | Remove any bean that replaced or referenced an aspect. |
| Both aspects at `Ordered.HIGHEST_PRECEDENCE`, relative order not fixed | one advisor at `Ordered.HIGHEST_PRECEDENCE + 100`; permit first, then lock | Check advice you ordered relative to Locksmith. |
| `LocksmithMetricsAutoConfiguration` | merged into `autoconfigure.LocksmithAutoConfiguration` | Remove it from `spring.autoconfigure.exclude` and from `exclude = ...` on `@SpringBootApplication`; the class no longer exists. |
| Redisson as the only backend, no backend abstraction | unchanged: Redisson only, no backend abstraction | Nothing. |
| `@RateLimit` (with Redisson's `RateType` for `type`) | removed | Use a rate-limiting library such as Bucket4j or Resilience4j. |
| `template.LocksmithRateLimitTemplate`, `template.callback.RateLimitCallback`, `handler.RateLimitSkipHandler`, `handler.ratelimit.RateLimitThrowExceptionHandler`, `handler.ratelimit.RateLimitReturnDefaultHandler`, `models.RateLimitContext`, `exception.RateLimitExceededException`, `exception.RateLimitConfigurationException`, `metrics.RateLimitMetrics`, `support.RateLimitConfig`, `support.RateLimitInitializer` | removed | Replace with the rate-limiting library's own types. |
| `locksmith.rate-limit.*` properties (`enabled`, `wait-time`, `key-prefix`, `debug`, `metrics-enabled`) | removed | Delete them. |
| `@DistributedLock(mode = ...)`, `@DistributedSemaphore(mode = ...)`, `AcquisitionMode` (`SKIP_IMMEDIATELY`, `WAIT_AND_SKIP`) | removed; `waitTime` alone decides | Drop `mode`. For `WAIT_AND_SKIP`, set `waitTime`; blank or zero means try once. |
| `locksmith.lock.wait-time`, `locksmith.semaphore.wait-time` (default 60s, used by `WAIT_AND_SKIP`) | removed; no property-level wait | Set `waitTime` on each annotation or builder that should wait. |
| Lock `leaseTime` blank: fixed lease from `locksmith.lock.lease-time` (10m), not renewed | blank: renewed while held (Redisson watchdog); a value: fixed lease, not renewed | Nothing for most methods. To keep a fixed lease, set `leaseTime` on the annotation. |
| `locksmith.lock.lease-time` | removed | Delete it; set `leaseTime` per annotation where a fixed lease is wanted. |
| `@DistributedLock(autoRenew = true)`, builder `autoRenew()` | removed; renewal is the default | Drop it and leave `leaseTime` blank or unset. |
| `@DistributedLock(onLeaseExpired = ...)`, `@DistributedSemaphore(onLeaseExpired = ...)`, `LeaseExpirationBehavior` | removed; an outrun lease logs one WARN at release and the result is returned | Drop the attribute. Alert on the WARN if you need to know. |
| `exception.LeaseExpiredException`, `exception.SemaphoreLeaseExpiredException` | removed | Remove the catch blocks. |
| A zero `leaseTime` accepted and passed to Redisson, which then renews a lock instead of expiring it | zero or below one millisecond rejected: at startup for annotations, `IllegalArgumentException` in the builders | Use at least `1ms`, or leave a lock lease blank for renewal. |
| `skipHandler = ...` attribute | `onFailure` plus `handler` | Replace with `onFailure = THROW`, `SKIP` or `HANDLER` (with `handler = ...`). |
| `handler.lock.LockThrowExceptionHandler`, `handler.semaphore.SemaphoreThrowExceptionHandler` (built-in, default) | `OnFailure.THROW`, the default | Remove the `skipHandler` attribute. |
| `handler.lock.LockReturnDefaultHandler`, `handler.semaphore.SemaphoreReturnDefaultHandler` | `OnFailure.SKIP` | Use `onFailure = OnFailure.SKIP`. The return values are the same table. |
| `handler.LockSkipHandler.handle(LockContext)`, `handler.SemaphoreSkipHandler.handle(SemaphoreContext)` | `lock.LockFailureHandler.onFailure(LockFailureContext)`, `semaphore.SemaphoreFailureHandler.onFailure(SemaphoreFailureContext)` | Implement the new interface and set `onFailure = OnFailure.HANDLER, handler = YourHandler.class`. |
| Handler looked up as a bean, else created by reflection with a no-argument constructor | exactly one bean of the handler type, checked at startup; never created by reflection | Register the handler as a Spring bean. |
| `models.LockContext(lockKey, methodName, method, args, returnType)` | `lock.LockFailureContext(key, method, args, waitTime)` | Use `key()`; use `method().getName()` and `method().getReturnType()` for the dropped fields. |
| `models.SemaphoreContext(semaphoreKey, methodName, method, args, returnType, permitId)` | `semaphore.SemaphoreFailureContext(key, permits, method, args, waitTime)` | As above; there is no permit id, since none was acquired. |
| `handler.DefaultValueResolver` (public) | internal | Stop using it; `OnFailure.SKIP` applies the same defaults. |
| `template.LocksmithLockTemplate` | `lock.LockOperations` bean | Inject `LockOperations`. |
| `template.LocksmithSemaphoreTemplate` | `semaphore.SemaphoreOperations` bean | Inject `SemaphoreOperations`. |
| `withKey(String)` | `key(String)` | Rename the call. |
| `LockOperationBuilder.lockType(LockType)` | `LockOperations.Builder.type(LockType)` | Rename the call. |
| `tryLock()`, `tryAcquire()` on the builders | `acquire()` | Rename the call. It never throws for "not acquired". |
| `execute(callback)` on the builders, `template.callback.LockCallback`, `template.callback.SemaphoreCallback` | removed; no callback-style API | Use try-with-resources on the handle and check `acquired()`. |
| `template.handle.LockHandle`, `template.handle.PermitHandle` | `lock.LockHandle`, `semaphore.PermitHandle` | Change the imports. |
| `LockHandle.isAcquired()`, `PermitHandle.isAcquired()` | `acquired()` | Rename the call. `key()` is new on both. |
| `LockHandle.acquired(...)`, `LockHandle.notAcquired()`, `PermitHandle.acquired(...)`, `PermitHandle.notAcquired()` (public factories) | removed; only the operations create handles | Mock the handle in tests instead. |
| `LocksmithLockTemplate.unlock(key)`, `unlock(key, type)` | removed; no unlock by name | Close the `LockHandle` that acquired the lock. |
| `LocksmithLockTemplate.isLocked(key)` | `LockOperations.isLocked(key, type)` | Pass the `LockType`. |
| `LocksmithSemaphoreTemplate.releasePermit(key, permitId)` | removed | Close the `PermitHandle`. |
| Fair, fenced, multi and spin locks | not offered, as in 3.x | Use Redisson directly if you need them. |
| `@DistributedSemaphore(permits = 5)`: `int`, default 1 | `permits = "5"`: `String`, required, `${...}` placeholders resolved | Quote the number, or use a placeholder such as `"${reports.max-concurrent}"`. |
| Semaphore count: first writer wins, WARN on mismatch, `<key>:meta` bucket in Redis | your code owns the count: the last writer wins, INFO on change, no metadata key | Delete the old `semaphore:*:meta` keys. Keep one count per key. |
| `exception.SemaphoreConfigurationException` for one key used with two counts in one JVM | removed; the last writer wins | Remove the catch blocks. |
| Key: evaluated only when the whole string is `#{...}`; `"user:#{#id}"` was a literal | template mode: literal text with `#{...}` islands, so `"user:#{#id}"` is evaluated | Check literal keys that contain `#{`. |
| Keys, durations and handlers checked on the first call | checked at startup; a misconfiguration fails the context refresh; an unknown key variable fails with a `-parameters` hint | Fix the reported annotation. |
| Key resolved to null or blank: `IllegalArgumentException` | `LocksmithConfigurationException` | Update catch blocks. |
| No bean references and no root object in key expressions | unchanged; `#root` fails at startup, a `@bean` reference fails when evaluated; `#this` works inside selections and projections | Pass the value as a method parameter. |
| `exception` package | `lock.LockNotAcquiredException`, `semaphore.SemaphoreNotAcquiredException`, `LocksmithConfigurationException`, `LocksmithException` | Change the imports. |
| Exceptions extend `RuntimeException` | all extend `LocksmithException`, which extends `RuntimeException` | Catch `LocksmithException` to handle every Locksmith error. |
| `LockNotAcquiredException(lockKey, methodName)`, `getLockKey()`, `getMethodName()` | `(key, waitTime)`, `key()`, `waitTime()`; message `Lock [<key>] not acquired within <waitTime>` | Use `key()`; the method name is no longer carried. |
| `SemaphoreNotAcquiredException(semaphoreKey, methodName)`, `getSemaphoreKey()`, `getMethodName()` | `(key, permits, waitTime)`, `key()`, `permits()`, `waitTime()`; message `Semaphore [<key>] permit not acquired within <waitTime> (permits <n>)` | Use `key()`. |
| `locksmith.lock.enabled`, `locksmith.semaphore.enabled` | `locksmith.enabled`, one switch for both; `false` logs one WARN | Replace with `locksmith.enabled`. |
| `locksmith.lock.key-prefix` (`lock:`), `locksmith.semaphore.key-prefix` (`semaphore:`) | `locksmith.key-prefix` (`locksmith:`) plus a fixed `lock:` or `semaphore:` part | Replace with `locksmith.key-prefix` if you need another prefix. |
| Redis keys `lock:<key>` and `semaphore:<key>` | `locksmith:lock:<key>` and `locksmith:semaphore:<key>` | 3.x and 4.0 instances do not exclude each other. Do not run both against the same keys at once. |
| `locksmith.semaphore.lease-time`: zero or negative replaced by the default | same property and default (5m); a value below one millisecond fails the startup | Set a value of at least `1ms`. |
| `locksmith.lock.debug`, `locksmith.semaphore.debug` | removed | Set `logging.level.in.riido.locksmith=DEBUG`. |
| `locksmith.lock.metrics-enabled`, `locksmith.semaphore.metrics-enabled` | removed; metrics are recorded whenever a `MeterRegistry` bean exists | Delete them. |
| `autoconfigure.LocksmithProperties` with nested `LockProperties`, `SemaphoreProperties`, `RateLimitProperties` and `defaults()` | `LocksmithProperties(enabled, keyPrefix, semaphore)` with nested `Semaphore(leaseTime)` | Update code that reads the properties. |
| Metrics `locksmith.lock.acquired`, `locksmith.semaphore.acquired` (counters) | `locksmith.acquire` timer with `outcome=acquired` | Count the timer, tagged `primitive=lock` or `semaphore`. |
| Metrics `locksmith.lock.skipped`, `locksmith.semaphore.skipped` with tag `reason=immediate` or `timeout` | `locksmith.acquire` with `outcome=skipped`; `outcome=interrupted` is new; no `reason` tag | Update dashboards and alerts. |
| Metrics `locksmith.lock.acquisition.time`, `locksmith.semaphore.acquisition.time` | `locksmith.acquire`, one record per attempt, the time spent waiting | Update dashboards. |
| Metrics `locksmith.lock.held.time`, `locksmith.semaphore.held.time` | `locksmith.held` with tag `primitive` | Update dashboards. |
| Metrics `locksmith.lock.lease.expired`, `locksmith.semaphore.lease.expired` | removed; an outrun lease is a WARN log line | Alert on the log line instead. |
| Gauge `locksmith.lock.autorenew.active` | removed | Remove it from dashboards. |
| No key tag on metrics | unchanged; no key tag, so the number of meters stays fixed | Nothing. |
| Packages `aspect`, `exception`, `handler` (with `lock`, `semaphore`, `ratelimit`), `models`, `template` (with `callback`, `handle`) | public packages are `in.riido.locksmith`, `lock`, `semaphore`, `autoconfigure`; `aop`, `support` and `metrics` are internal | Change the imports as the rows above say. |

## [3.0.3] - 2026-03-06

### Changed
- Refactor templates to builder-only API with try-with-resources support (#53) (#54)
- Refactor imports to use `org.redisson.api.RateType` consistently across rate limiting classes
- Refactor template callback and handle subpackages

### Fixed
- Fix Javadoc errors from stale method references after builder API refactor
- Fix logging, error handling, and behavioral inconsistencies across coordination primitives (#52)

## [3.0.2] - 2026-03-05

### Dependencies
- Bump `org.redisson:redisson` from 4.2.0 to 4.3.0 (#50)
- Bump `org.springframework.boot:spring-boot-dependencies` from 4.0.2 to 4.0.3 (#47)
- Bump `org.apache.maven.plugins:maven-surefire-plugin` from 3.5.4 to 3.5.5 (#48)

### CI
- Bump `actions/upload-artifact` from 6 to 7 (#49)

## [3.0.1] - 2026-02-14

### Fixed
- Propagate `InterruptedException` from user methods inside `@DistributedLock`, `@DistributedSemaphore`, and template callbacks instead of silently swallowing it
- Record rate-limit execution time metrics even when the method throws (wrap with try-finally in `RateLimitAspect` and `LocksmithRateLimitTemplate`)
- Only warn about `onLeaseExpired` when `THROW_EXCEPTION` is used with `autoRenew`, not for the default `LOG_WARNING` value
- Log full exception stack traces in semaphore permit release instead of just the message
- Wrap `DurationResolver` parse errors with helpful context about the invalid input and expected formats

### Changed
- Document SpEL expression cache bounding semantics in `SpELKeyResolver`

## [3.0.0] - 2026-02-07

### Added
- **Distributed Rate Limiting** - New `@RateLimit` annotation for controlling request throughput across distributed systems (#44, #7)
  - Configurable `permits` and `interval` (e.g., 100 requests per minute)
  - Support for `OVERALL` (shared) and `PER_CLIENT` (per Redisson instance) rate types via `RateType` enum
  - `SKIP_IMMEDIATELY` and `WAIT_AND_SKIP` acquisition modes
  - `RateLimitSkipHandler` interface with `RateLimitThrowExceptionHandler` and `RateLimitReturnDefaultHandler` built-in handlers
  - `RateLimitContext` record for handler context
  - `RateLimitExceededException` for when rate limit is exceeded
  - `LocksmithRateLimitTemplate` for programmatic rate limiting with fluent builder
  - `RateLimitCallback` functional interface
  - `RateLimitMetrics` - Micrometer metrics integration (opt-in via `locksmith.rate-limit.metrics-enabled`)
  - SpEL expression support for dynamic keys
- **Enabled Property** - Conditionally disable lock, semaphore, and rate-limit components
  - `locksmith.lock.enabled` (default: true)
  - `locksmith.semaphore.enabled` (default: true)
  - `locksmith.rate-limit.enabled` (default: true)
  - Uses `@ConditionalOnProperty` to conditionally create aspects and templates

### Changed
- Thread safety documentation added to Context records clarifying that args arrays are read-only by convention

### Dependencies
- Bumped Redisson from 4.1.0 to 4.2.0
- Bumped maven-compiler-plugin from 3.14.1 to 3.15.0

## [2.1.0] - 2026-01-26

### Added
- **Micrometer Metrics Integration** - Optional observability for lock and semaphore operations (#30)
  - `LockMetrics` with counters, timers, and gauges for lock operations
  - `SemaphoreMetrics` with parallel metrics for semaphore operations
  - `LocksmithMetricsAutoConfiguration` for conditional bean creation
  - Metrics are opt-in via `locksmith.lock.metrics-enabled` and `locksmith.semaphore.metrics-enabled` properties
  - Graceful degradation when Micrometer is not on classpath
- **Programmatic Templates** - Alternative to annotations for lock and semaphore operations (#42)
  - `LocksmithLockTemplate` for programmatic lock operations with builder pattern
  - `LocksmithSemaphoreTemplate` for programmatic semaphore operations with builder pattern
  - `LockCallback` and `SemaphoreCallback` functional interfaces
  - Support for auto-renew, custom timing, and all lock types

### Lock Metrics
- `locksmith.lock.acquired` - successful acquisitions
- `locksmith.lock.skipped` (tagged by reason) - skipped acquisitions
- `locksmith.lock.lease.expired` - lease expiration events
- `locksmith.lock.acquisition.time` - acquisition duration
- `locksmith.lock.held.time` - time lock was held
- `locksmith.lock.autorenew.active` - gauge of active auto-renewed locks

### Semaphore Metrics
- `locksmith.semaphore.acquired` - successful permit acquisitions
- `locksmith.semaphore.skipped` (tagged by reason) - skipped acquisitions
- `locksmith.semaphore.lease.expired` - lease expiration events
- `locksmith.semaphore.acquisition.time` - acquisition duration
- `locksmith.semaphore.held.time` - time permit was held

### Fixed
- Semaphore permit consistency validation in `LocksmithSemaphoreTemplate`
- Metrics skip reason logic using explicit mode instead of waitTime proxy
- `LockOperationBuilder` now warns when `leaseTime()` overrides `autoRenew()`

## [2.0.0] - 2026-01-16

### Added
- **Distributed Semaphores** - New `@DistributedSemaphore` annotation for permit-based concurrency control (#8)
- `SemaphoreSkipHandler` interface for custom semaphore skip behavior
- `SemaphoreThrowExceptionHandler` and `SemaphoreReturnDefaultHandler` built-in handlers
- `SemaphoreContext` record for handler context
- `SemaphoreNotAcquiredException` and `SemaphoreLeaseExpiredException` exceptions
- Spring bean dependency injection support for skip handlers
- `DefaultValueResolver` utility for shared default value resolution
- `SpELKeyResolver` and `DurationResolver` shared utilities

### Changed
- Handler resolution now checks Spring ApplicationContext first, falls back to reflection
- Extracted common utilities to `support` package
- Improved handler caching with instance-level cache per aspect

## [1.4.3] - 2026-01-03

### Added
- Check `isHeldByCurrentThread()` before unlocking with warning log for expired locks (#36)

### Changed
- Improved virtual thread compatibility with better lock ownership verification (#36)

## [1.4.2] - 2026-01-01

### Added
- Logging of Redisson and Spring Boot versions during initialization

## [1.4.1] - 2025-12-27

### Added
- SpEL expression caching using ConcurrentHashMap (#34)

### Performance
- 43% reduction in P99 latency
- 75% reduction in throughput variation
- 17% increase in concurrent throughput
- 14% reduction in CPU utilization

### Changed
- Improved CI/CD pipeline with Java 17, 21, and 25 matrix testing
- Optimized test execution by excluding performance tests from CI

## [1.4.0] - 2025-12-27

### Breaking Changes
- SpEL expressions now require `#{...}` wrapper syntax - change `#userId` to `#{#userId}` (#33)
- Literal keys can now contain `#` character (e.g., `order#123`)

### Added
- Handler instance caching for better performance (#32)
- Debug mode configuration: `locksmith.debug=true`
- Comprehensive SpEL test coverage (38 tests)

### Changed
- Removed explicit version specs for Spring Boot and SLF4J
- Improved Docker availability detection in tests

## [1.3.1] - 2025-12-24

### Added
- Virtual thread integration tests for Java 21+ (10 test cases)
- Comprehensive test coverage for concurrent access and stress scenarios (#29)

### Fixed
- Javadoc warnings in LockContext compact constructor

### Dependencies
- Updated testcontainers to Spring Boot managed versions
- Bumped actions/checkout from 4 to 6
- Bumped actions/setup-java from 4 to 5
- Bumped actions/upload-artifact from 4 to 6

## [1.3.0] - 2025-12-23

### Added
- Auto-renew lease time support using Redisson watchdog mechanism (#20, #21)
- Input validation for LockContext with null checks (#15, #19)

### Fixed
- Virtual thread compatibility by removing `isHeldByCurrentThread()` check to prevent lock leaks (#22, #23)

## [1.2.2] - 2025-12-23

### Fixed
- Sub-second duration precision loss by using TimeUnit.MILLISECONDS (#17)

### Added
- Wrapper type support to ReturnDefaultHandler (#16)
- GitHub Actions CI workflow (#18)
- Tests for sub-second duration precision

## [1.2.1] - 2025-12-21

### Added
- JaCoCo plugin for code coverage
- Initialization logging in LocksmithAutoConfiguration
- `toString()` method to LocksmithProperties
- spring-configuration-metadata.json for IDE autocompletion

### Fixed
- Javadoc warnings across multiple classes

## [1.2.0] - 2025-12-21

### Added
- Read/Write lock support with LockType enum (REENTRANT, READ, WRITE)
- Lease timeout detection with LeaseExpirationBehavior
- Custom skip handlers via LockSkipHandler interface
- LockContext record for handler context
- ThrowExceptionHandler and ReturnDefaultHandler
- LeaseExpiredException for lease violations

### Breaking Changes
- Removed SkipBehavior enum - use skipHandler parameter
- Removed onSkip attribute - use skipHandler = ReturnDefaultHandler.class

### Fixed
- Redundant null check in SpEL key resolution

## [1.0.1] - 2025-12-20

### Changed
- Replaced time properties with Duration objects supporting simple (10m, 30s) and ISO-8601 (PT10M) formats

## [1.0.0] - 2025-12-20

### Added
- Initial release
- @DistributedLock annotation with SpEL support
- Lock acquisition modes (SKIP_IMMEDIATELY, WAIT_AND_SKIP)
- Autoconfiguration for Spring Boot 4.x
- Redisson integration for distributed locks
- 29 unit tests
