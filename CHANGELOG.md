# Changelog

All notable changes to this project will be documented in this file.

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
