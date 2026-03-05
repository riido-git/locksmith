# Locksmith

[![Maven Central](https://img.shields.io/maven-central/v/in.riido/locksmith-spring-boot-starter)](https://central.sonatype.com/artifact/in.riido/locksmith-spring-boot-starter)

A Spring Boot starter for Redis-based distributed locking, semaphores, and rate limiting using annotations.

## Overview

Locksmith provides three coordination primitives for distributed systems:

| Primitive | Purpose | Example Use Case |
|-----------|---------|------------------|
| `@DistributedLock` | Exclusive access - only one instance executes at a time | Payment processing, scheduled jobs |
| `@DistributedSemaphore` | Limited concurrency - up to N instances execute simultaneously | Connection pooling, batch processing |
| `@RateLimit` | Throughput control - limit requests per time interval | API rate limiting, throttling |

## Requirements

- Java 17+
- Spring Boot 4.0+
- Redis
- Redisson 4.0+

## Installation

Add to your `pom.xml`:

```xml
<dependency>
    <groupId>in.riido</groupId>
    <artifactId>locksmith-spring-boot-starter</artifactId>
    <version>3.0.2</version>
</dependency>

<dependency>
    <groupId>org.redisson</groupId>
    <artifactId>redisson</artifactId>
    <version>4.3.0</version>
</dependency>

<dependency>
    <groupId>org.aspectj</groupId>
    <artifactId>aspectjweaver</artifactId>
</dependency>
```

For Gradle:

```groovy
implementation 'in.riido:locksmith-spring-boot-starter:3.0.2'
implementation 'org.redisson:redisson:4.3.0'
implementation 'org.aspectj:aspectjweaver'
```

## Quick Start

### 1. Configure Redis Connection

Provide a `RedissonClient` bean:

```java
@Configuration
public class RedisConfig {

    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.useSingleServer()
              .setAddress("redis://localhost:6379");
        return Redisson.create(config);
    }
}
```

### 2. Use Annotations

```java
@Service
public class OrderService {

    // Only one instance processes this order at a time
    @DistributedLock(key = "#{'order-' + #orderId}")
    public void processOrder(String orderId) {
        // Critical section
    }

    // Up to 5 concurrent API calls across all instances
    @DistributedSemaphore(key = "external-api", permits = 5)
    public Response callExternalApi() {
        return httpClient.get("/api/data");
    }

    // Maximum 100 requests per minute
    @RateLimit(key = "api-endpoint", permits = 100, interval = "1m")
    public Response handleRequest() {
        return processRequest();
    }
}
```

## Distributed Locks

Use `@DistributedLock` when only one instance should execute a method at a time.

```java
// Basic lock
@DistributedLock(key = "my-task")
public void exclusiveTask() { }

// Dynamic key using SpEL (must use #{...} wrapper)
@DistributedLock(key = "#{#userId}")
public void processUser(String userId) { }

// Wait up to 30 seconds for lock
@DistributedLock(key = "resource", mode = AcquisitionMode.WAIT_AND_SKIP, waitTime = "30s")
public void waitForLock() { }

// Auto-renew for long-running tasks
@DistributedLock(key = "long-task", autoRenew = true)
public void longRunningTask() { }

// Read/Write locks for concurrent reads
@DistributedLock(key = "data", type = LockType.READ)
public Data readData() { }

@DistributedLock(key = "data", type = LockType.WRITE)
public void writeData(Data data) { }
```

**Handling Lock Failures:**

```java
// Default: throws LockNotAcquiredException
@DistributedLock(key = "task")
public void task() { }

// Silent skip: returns null/default value
@DistributedLock(key = "task", skipHandler = LockReturnDefaultHandler.class)
public void task() { }
```

## Distributed Semaphores

Use `@DistributedSemaphore` to limit concurrent executions to N instances.

```java
// Allow 10 concurrent executions
@DistributedSemaphore(key = "db-pool", permits = 10)
public void queryDatabase() { }

// Per-user concurrency limit
@DistributedSemaphore(key = "#{#userId}", permits = 3)
public void userOperation(String userId) { }

// Wait for permit
@DistributedSemaphore(key = "pool", permits = 5, mode = AcquisitionMode.WAIT_AND_SKIP, waitTime = "30s")
public void waitForPermit() { }
```

## Rate Limiting

Use `@RateLimit` to control request throughput over time.

```java
// 10 requests per second (default)
@RateLimit(key = "api")
public void apiCall() { }

// 100 requests per minute
@RateLimit(key = "heavy-api", permits = 100, interval = "1m")
public void heavyOperation() { }

// Per-user rate limiting
@RateLimit(key = "#{#userId}", permits = 60, interval = "1m")
public void userRequest(String userId) { }

// Per-instance rate limiting
@RateLimit(key = "local-api", permits = 50, interval = "1s", type = RateType.PER_CLIENT)
public void localOperation() { }
```

## Programmatic API

For scenarios where annotations are not suitable:

```java
@Service
public class MyService {

    private final LocksmithLockTemplate lockTemplate;
    private final LocksmithSemaphoreTemplate semaphoreTemplate;
    private final LocksmithRateLimitTemplate rateLimitTemplate;

    // Lock with callback
    public String withLock() {
        return lockTemplate.executeWithLock("my-key", () -> {
            return computeResult();
        });
    }

    // Lock with builder
    public void customLock() {
        lockTemplate.forKey("my-key")
            .waitTime(Duration.ofSeconds(30))
            .leaseTime(Duration.ofMinutes(5))
            .lockType(LockType.WRITE)
            .execute(() -> doWork());
    }

    // Semaphore with callback
    public String withSemaphore() {
        return semaphoreTemplate.executeWithPermit("pool", 5, () -> {
            return callApi();
        });
    }

    // Rate limit with callback
    public String withRateLimit() {
        return rateLimitTemplate.executeWithRateLimit("api", () -> {
            return processRequest();
        });
    }
}
```

## Configuration

```yaml
locksmith:
  lock:
    enabled: true           # Enable/disable locks
    lease-time: 10m         # Auto-release time
    wait-time: 60s          # Wait time for WAIT_AND_SKIP
    key-prefix: "lock:"     # Redis key prefix
    metrics-enabled: false  # Micrometer metrics
  semaphore:
    enabled: true
    lease-time: 5m
    wait-time: 60s
    key-prefix: "semaphore:"
    metrics-enabled: false
  rate-limit:
    enabled: true
    wait-time: 60s
    key-prefix: "ratelimit:"
    metrics-enabled: false
```

## SpEL Key Syntax

Dynamic keys use Spring Expression Language. **SpEL expressions must be wrapped in `#{...}`:**

| Expression | Type | Result |
|------------|------|--------|
| `"my-task"` | Literal | `my-task` |
| `"order#123"` | Literal | `order#123` |
| `"#{#userId}"` | SpEL | Value of `userId` parameter |
| `"#{'user-' + #id}"` | SpEL | `user-42` (concatenation) |
| `"#{#order.customerId}"` | SpEL | Property access |

## Exception Handling

```java
try {
    lockedMethod();
} catch (LockNotAcquiredException e) {
    // Lock was not acquired
} catch (LeaseExpiredException e) {
    // Method exceeded lease time
}

try {
    semaphoreMethod();
} catch (SemaphoreNotAcquiredException e) {
    // No permit available
}

try {
    rateLimitedMethod();
} catch (RateLimitExceededException e) {
    // Rate limit exceeded
}
```

## Documentation

For detailed documentation, see the **[Wiki](https://github.com/riido-git/locksmith/wiki)**:

- [Installation](https://github.com/riido-git/locksmith/wiki/Installation)
- [Configuration](https://github.com/riido-git/locksmith/wiki/Configuration)
- [Distributed Locks](https://github.com/riido-git/locksmith/wiki/Distributed-Locks)
- [Distributed Semaphores](https://github.com/riido-git/locksmith/wiki/Distributed-Semaphores)
- [Rate Limiting](https://github.com/riido-git/locksmith/wiki/Rate-Limiting)
- [Dynamic Keys with SpEL](https://github.com/riido-git/locksmith/wiki/Dynamic-Keys-with-SpEL)
- [Lock Types (Read/Write)](https://github.com/riido-git/locksmith/wiki/Lock-Types)
- [Auto-Renew Lease Time](https://github.com/riido-git/locksmith/wiki/Auto-Renew-Lease-Time)
- [Skip Handlers](https://github.com/riido-git/locksmith/wiki/Skip-Handlers)
- [Programmatic Templates](https://github.com/riido-git/locksmith/wiki/Programmatic-Templates)
- [Micrometer Metrics](https://github.com/riido-git/locksmith/wiki/Micrometer-Metrics)
- [High Concurrency Best Practices](https://github.com/riido-git/locksmith/wiki/High-Concurrency-Best-Practices)
- [Troubleshooting](https://github.com/riido-git/locksmith/wiki/Troubleshooting)

## Issues

Found a bug or have a feature request? [Create an issue](https://github.com/riido-git/locksmith/issues).

## License

Apache License 2.0
