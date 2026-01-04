# Changelog

All notable changes to this project will be documented in this file.

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
- Auto-configuration for Spring Boot 4.x
- Redisson integration for distributed locks
- 29 unit tests
