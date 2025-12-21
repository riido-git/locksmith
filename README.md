# Locksmith

A Spring Boot starter for Redis-based distributed locking using annotations. Ensures only one instance across all servers executes a method at a time.

## Features

- Simple `@DistributedLock` annotation for methods
- Spring Expression Language (SpEL) support for dynamic lock keys
- Read/Write lock support for concurrent reads with exclusive writes
- Lease timeout detection to catch methods exceeding lock duration
- Custom skip handlers for advanced lock failure handling
- Configurable lock acquisition modes and skip handlers
- Auto-configuration for Spring Boot 4.x
- Uses Redisson for reliable distributed locks

## Requirements

- Java 17+
- Spring Boot 4.0+
- Redis server
- Redisson 4.0+

## Installation

Add the dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>in.riido</groupId>
    <artifactId>locksmith-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

You must also include Redisson and AspectJ in your project:

```xml
<dependency>
    <groupId>org.redisson</groupId>
    <artifactId>redisson</artifactId>
    <version>4.0.0</version>
</dependency>

<dependency>
    <groupId>org.aspectj</groupId>
    <artifactId>aspectjweaver</artifactId>
</dependency>
```

## Configuration

### Provide a RedissonClient Bean

Locksmith requires a `RedissonClient` bean. You must configure this yourself:

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

### Application Properties

Configure locksmith in your `application.properties` or `application.yml`:

```yaml
locksmith:
  lease-time: 10m       # Lock auto-release time (default: 10m)
  wait-time: 60s        # Wait time for WAIT_AND_SKIP mode (default: 60s)
  key-prefix: "lock:"   # Redis key prefix (default: "lock:")
```

## Usage

### Basic Usage

```java
@Service
public class MyService {

    @DistributedLock(key = "critical-task")
    public void criticalTask() {
        // Only one instance executes this at a time
    }
}
```

### Scheduled Tasks

For scheduled tasks, use `ReturnDefaultHandler` to silently skip if lock is held:

```java
@Service
public class SchedulerService {

    @Scheduled(cron = "0 0 3 * * ?")
    @DistributedLock(key = "cleanup-job", skipHandler = ReturnDefaultHandler.class)
    public void dailyCleanup() {
        // Runs on only one instance
    }
}
```

### Dynamic Keys with SpEL

Use Spring Expression Language for dynamic lock keys:

```java
// Lock per user ID
@DistributedLock(key = "#userId")
public void processUser(String userId) { }

// Lock using object property
@DistributedLock(key = "#order.id")
public void processOrder(Order order) { }

// Lock with concatenation
@DistributedLock(key = "'user-' + #userId")
public void updateUser(Long userId) { }
```

### Wait for Lock

Use `WAIT_AND_SKIP` mode to wait for the lock before giving up:

```java
@DistributedLock(
    key = "resource-lock",
    mode = LockAcquisitionMode.WAIT_AND_SKIP,
    waitTime = "30s"
)
public void accessResource() { }
```

### Custom Lease Time

Override the default lease time per method:

```java
@DistributedLock(key = "long-task", leaseTime = "30m")
public void longRunningTask() { }
```

### Read/Write Locks

Use read/write locks when you need concurrent reads but exclusive writes:

```java
@Service
public class ResourceService {

    // Multiple instances can read concurrently
    @DistributedLock(key = "resource", type = LockType.READ)
    public Data readResource() {
        return loadData();
    }

    // Only one instance can write at a time, blocks all readers
    @DistributedLock(key = "resource", type = LockType.WRITE)
    public void writeResource(Data data) {
        saveData(data);
    }
}
```

| Lock Type | Behavior |
|-----------|----------|
| `REENTRANT` | Exclusive lock (default) - only one holder at a time |
| `READ` | Shared lock - multiple concurrent readers allowed |
| `WRITE` | Exclusive lock - no readers or writers allowed simultaneously |

**Important:** When using READ/WRITE locks, all methods accessing the same resource must use the same lock key to ensure proper synchronization.

### Lease Timeout Detection

Detect when a method's execution time exceeds the configured lease duration, which could indicate the lock expired during execution:

```java
@Service
public class DataService {

    // Log warning if execution exceeds lease time (default behavior)
    @DistributedLock(key = "data-sync", leaseTime = "5m")
    public void syncData() {
        // If this takes > 5 minutes, a warning is logged
    }

    // Throw exception if execution exceeds lease time
    @DistributedLock(
        key = "critical-task",
        leaseTime = "10m",
        onLeaseExpired = LeaseExpirationBehavior.THROW_EXCEPTION
    )
    public void criticalTask() {
        // If this takes > 10 minutes, LeaseExpiredException is thrown after completion
    }

    // Ignore lease expiration (not recommended for critical operations)
    @DistributedLock(
        key = "best-effort-task",
        onLeaseExpired = LeaseExpirationBehavior.IGNORE
    )
    public void bestEffortTask() { }
}
```

Handle `LeaseExpiredException`:

```java
try {
    dataService.criticalTask();
} catch (LeaseExpiredException e) {
    log.error("Lock expired during execution: {} took {}ms but lease was {}ms",
        e.getMethodName(), e.getExecutionTimeMs(), e.getLeaseTimeMs());
    // Consider compensating actions
}
```

### Custom Skip Handlers

For advanced lock failure handling, implement the `LockSkipHandler` interface:

```java
public class AlertingSkipHandler implements LockSkipHandler {

    @Override
    public Object handle(LockContext context) {
        // Send alert, log to specific system, or execute alternative logic
        alertService.sendAlert("Lock not acquired: " + context.lockKey());

        // Return a fallback value
        return "fallback-result";
    }
}

@DistributedLock(key = "critical-task", skipHandler = AlertingSkipHandler.class)
public String criticalTask() { }
```

The `LockContext` provides:
- `lockKey()` - The Redis lock key
- `methodName()` - The formatted method name
- `method()` - The intercepted Method
- `args()` - The method arguments
- `returnType()` - The method's return type

Built-in handlers:
- `ThrowExceptionHandler` (default) - Throws `LockNotAcquiredException`
- `ReturnDefaultHandler` - Returns null/default values

## Annotation Reference

### `@DistributedLock`

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `key` | String | (required) | Lock key, supports SpEL |
| `type` | LockType | `REENTRANT` | Type of lock (REENTRANT, READ, WRITE) |
| `mode` | LockAcquisitionMode | `SKIP_IMMEDIATELY` | How to acquire the lock |
| `leaseTime` | String | `""` (use config) | Lock auto-release time (e.g., "10m", "30s") |
| `waitTime` | String | `""` (use config) | Wait time for WAIT_AND_SKIP (e.g., "30s", "1m") |
| `skipHandler` | Class | `ThrowExceptionHandler` | Handler for lock acquisition failures |
| `onLeaseExpired` | LeaseExpirationBehavior | `LOG_WARNING` | Behavior when execution exceeds lease time |

### `LockType`

| Value | Description |
|-------|-------------|
| `REENTRANT` | Exclusive lock - only one holder at a time (default) |
| `READ` | Shared lock - multiple concurrent readers allowed |
| `WRITE` | Exclusive lock - blocks all readers and writers |

### `LockAcquisitionMode`

| Value | Description |
|-------|-------------|
| `SKIP_IMMEDIATELY` | Fail immediately if lock is held |
| `WAIT_AND_SKIP` | Wait up to `waitTime` before failing |

### `LeaseExpirationBehavior`

| Value | Description |
|-------|-------------|
| `LOG_WARNING` | Log a warning message (default) |
| `THROW_EXCEPTION` | Throw `LeaseExpiredException` after method completes |
| `IGNORE` | Silently ignore lease expiration |

## Exception Handling

When using `ThrowExceptionHandler` (default), catch `LockNotAcquiredException`:

```java
try {
    myService.criticalTask();
} catch (LockNotAcquiredException e) {
    log.warn("Could not acquire lock: {}", e.getLockKey());
    // Handle accordingly
}
```

## How It Works

1. When a method with `@DistributedLock` is called, the aspect intercepts it
2. It resolves the lock key (evaluating SpEL if needed)
3. Attempts to acquire a Redis lock via Redisson
4. If acquired: executes the method, then releases the lock
5. If not acquired: invokes the configured `skipHandler`

The aspect runs with `Ordered.HIGHEST_PRECEDENCE` to ensure locks are acquired before transactions begin.

## License

Apache License 2.0
