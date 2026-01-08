# Locksmith

[![Maven Central](https://img.shields.io/maven-central/v/in.riido/locksmith-spring-boot-starter)](https://central.sonatype.com/artifact/in.riido/locksmith-spring-boot-starter)

A Spring Boot starter for Redis-based distributed locking and semaphores using annotations. Ensures controlled concurrent access across all servers.

## Features

- **`@DistributedLock`** - Exclusive/read-write locking for critical sections
- **`@DistributedSemaphore`** - Permit-based concurrency control (limit N concurrent executions)
- Spring Expression Language (SpEL) support for dynamic keys
- Read/Write lock support for concurrent reads with exclusive writes
- Auto-renew lease time for long-running tasks using Redisson's watchdog
- Lease timeout detection to catch methods exceeding lease duration
- Custom skip handlers for advanced failure handling
- Configurable acquisition modes and skip handlers
- Auto-configuration for Spring Boot 4.x
- Uses Redisson for reliable distributed primitives

## Requirements

- Java 17+
- Spring Boot 4.0+
- Redis server
- Redisson 4.0+

## Installation

Add the dependency to your `pom.xml` (available on [Maven Central](https://central.sonatype.com/artifact/in.riido/locksmith-spring-boot-starter)):

```xml
<dependency>
    <groupId>in.riido</groupId>
    <artifactId>locksmith-spring-boot-starter</artifactId>
    <version>2.0.0</version>
</dependency>
```

You must also include Redisson and AspectJ in your project:

```xml
<dependency>
    <groupId>org.redisson</groupId>
    <artifactId>redisson</artifactId>
    <version>4.1.0</version>
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
  lock:
    lease-time: 10m       # Lock auto-release time (default: 10m)
    wait-time: 60s        # Wait time for WAIT_AND_SKIP mode (default: 60s)
    key-prefix: "lock:"   # Redis key prefix (default: "lock:")
    debug: false          # Enable debug logging (default: false)
  semaphore:
    lease-time: 5m        # Permit lease time (default: 5m)
    wait-time: 60s        # Wait time for WAIT_AND_SKIP mode (default: 60s)
    key-prefix: "semaphore:"  # Redis key prefix (default: "semaphore:")
    debug: false          # Enable debug logging (default: false)
```

> **Note:** In v2.0.0, configuration moved from `locksmith.lease-time` to `locksmith.lock.lease-time`. See migration guide below.

---

## Distributed Locks

Use `@DistributedLock` when you need exclusive access to a resource - only one instance across all servers can execute a method at a time.

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

Use Spring Expression Language (SpEL) for dynamic lock keys. SpEL expressions must be wrapped in `#{...}` syntax:

```java
// Lock per user ID
@DistributedLock(key = "#{#userId}")
public void processUser(String userId) { }

// Lock using object property
@DistributedLock(key = "#{#order.id}")
public void processOrder(Order order) { }

// Lock with concatenation
@DistributedLock(key = "#{'user-' + #userId}")
public void updateUser(Long userId) { }

// Lock using parameter by position
@DistributedLock(key = "#{#p0}")  // or #{#a0}
public void processFirst(String firstArg) { }

// Lock with conditional expression
@DistributedLock(key = "#{#amount > 1000 ? 'large' : 'small'}")
public void processPayment(double amount) { }

// Lock using method call
@DistributedLock(key = "#{#user.getId()}")
public void processUser(User user) { }

// Lock using static method
@DistributedLock(key = "#{T(java.lang.String).valueOf(#orderId)}")
public void processOrder(Long orderId) { }
```

**Important Notes:**
- **SpEL expressions require `#{...}` wrapper** - Without it, the key is treated as a literal string
- **Literal keys can contain `#`** - Keys like `order#123` or `task#1` work as literals (no SpEL evaluation)
- **Parameter names** - Access method parameters using `#paramName`, `#p0`, or `#a0`
- **Object properties** - Access nested properties with `#object.property` or `#object.method()`
- **Operators** - Use SpEL operators: `+`, `-`, `*`, `/`, `>`, `<`, `==`, `? :`, etc.

**Examples:**

| Key Expression | Type | Resolves To |
|---------------|------|-------------|
| `"#{#userId}"` | SpEL | Value of `userId` parameter |
| `"#userId"` | Literal | String `"#userId"` (not evaluated) |
| `"order#123"` | Literal | String `"order#123"` |
| `"#{'user-' + #id}"` | SpEL | Concatenated string like `"user-42"` |
| `"#{#user.name}"` | SpEL | Value of `user.name` property |

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

### Custom Wait Time

Override the default wait time per method:

```java
@DistributedLock(
    key = "resource-lock",
    mode = LockAcquisitionMode.WAIT_AND_SKIP,
    waitTime = "2m"
)
public void accessResource() { }
```

### Custom Lease Time

Override the default lease time per method:

```java
@DistributedLock(key = "long-task", leaseTime = "30m")
public void longRunningTask() { }
```

### Auto-Renew for Long-Running Tasks

For tasks with unpredictable duration, enable `autoRenew` to automatically extend the lock lease time using Redisson's watchdog mechanism:

```java
@DistributedLock(key = "long-task", autoRenew = true)
public void longRunningTask() {
    // Lock automatically extends during execution
    // Safe for tasks with unpredictable duration
}
```

When `autoRenew` is enabled:
- Redisson automatically extends the lock every ~10 seconds (lockWatchdogTimeout / 3, where lockWatchdogTimeout defaults to 30 seconds)
- The lock is released when the method completes or the thread terminates
- `leaseTime` is ignored (a warning is logged if specified)
- `onLeaseExpired` has no effect (the lock never expires during execution)

**Trade-off:** If the method hangs indefinitely, the lock will be held until the thread dies or the application shuts down.

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

### `@DistributedLock` Annotation Reference

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `key` | String | (required) | Lock key - literal string or SpEL expression wrapped in `#{...}` |
| `type` | LockType | `REENTRANT` | Type of lock (REENTRANT, READ, WRITE) |
| `mode` | LockAcquisitionMode | `SKIP_IMMEDIATELY` | How to acquire the lock |
| `leaseTime` | String | `""` (use config) | Lock auto-release time (e.g., "10m", "30s") |
| `waitTime` | String | `""` (use config) | Wait time for WAIT_AND_SKIP (e.g., "30s", "1m") |
| `autoRenew` | boolean | `false` | Enable automatic lease renewal via Redisson's watchdog |
| `skipHandler` | Class | `ThrowExceptionHandler` | Handler for lock acquisition failures |
| `onLeaseExpired` | LeaseExpirationBehavior | `LOG_WARNING` | Behavior when execution exceeds lease time |

**`LockType`**

| Value | Description |
|-------|-------------|
| `REENTRANT` | Exclusive lock - only one holder at a time (default) |
| `READ` | Shared lock - multiple concurrent readers allowed |
| `WRITE` | Exclusive lock - blocks all readers and writers |

**`LockAcquisitionMode`**

| Value | Description |
|-------|-------------|
| `SKIP_IMMEDIATELY` | Fail immediately if lock is held |
| `WAIT_AND_SKIP` | Wait up to `waitTime` before failing |

**`LeaseExpirationBehavior`**

| Value | Description |
|-------|-------------|
| `LOG_WARNING` | Log a warning message (default) |
| `THROW_EXCEPTION` | Throw `LeaseExpiredException` after method completes |
| `IGNORE` | Silently ignore lease expiration |

### Lock Exception Handling

When using `ThrowExceptionHandler` (default), catch `LockNotAcquiredException`:

```java
try {
    myService.criticalTask();
} catch (LockNotAcquiredException e) {
    log.warn("Could not acquire lock: {}", e.getLockKey());
    // Handle accordingly
}
```

### How Locks Work

1. When a method with `@DistributedLock` is called, the aspect intercepts it
2. It resolves the lock key:
   - If key is wrapped in `#{...}`, evaluates it as a SpEL expression
   - Otherwise, treats it as a literal string (even if it contains `#`)
3. Attempts to acquire a Redis lock via Redisson
4. If acquired: executes the method, then releases the lock
5. If not acquired: invokes the configured `skipHandler`

The aspect runs with `Ordered.HIGHEST_PRECEDENCE` to ensure locks are acquired before transactions begin.

**SpEL Evaluation**: Keys starting with `#{` and ending with `}` are evaluated as SpEL expressions. All other keys are treated as literal strings, allowing keys like `order#123` or `#task` to work without escaping.

---

## Distributed Semaphores

Use `@DistributedSemaphore` when you need to limit the number of concurrent executions across all servers, rather than exclusive locking.

### Basic Semaphore Usage

```java
@Service
public class RateLimitedService {

    // Allow up to 5 concurrent executions across all servers
    @DistributedSemaphore(key = "api-calls", permits = 5)
    public void callExternalApi() {
        // Only 5 instances can execute this simultaneously
    }
}
```

### Semaphore with Dynamic Keys

Use SpEL expressions for per-resource rate limiting:

```java
// Limit concurrent operations per user
@DistributedSemaphore(key = "#{#userId}", permits = 3)
public void processUserRequest(String userId) { }

// Limit concurrent operations per tenant
@DistributedSemaphore(key = "#{#request.tenantId}", permits = 10)
public void processTenantRequest(Request request) { }
```

### Wait for Permit

Use `WAIT_AND_SKIP` mode to wait for an available permit:

```java
@DistributedSemaphore(
    key = "resource-pool",
    permits = 10,
    mode = LockAcquisitionMode.WAIT_AND_SKIP,
    waitTime = "30s"
)
public void accessResourcePool() { }
```

### Custom Lease Time

Override the default lease time per method:

```java
@DistributedSemaphore(key = "long-task", permits = 3, leaseTime = "30m")
public void longRunningTask() { }
```

### Lease Timeout Detection

Detect when execution time exceeds the configured lease duration:

```java
// Throw exception if execution exceeds lease time
@DistributedSemaphore(
    key = "critical-task",
    permits = 5,
    leaseTime = "10m",
    onLeaseExpired = LeaseExpirationBehavior.THROW_EXCEPTION
)
public void criticalTask() {
    // If this takes > 10 minutes, SemaphoreLeaseExpiredException is thrown after completion
}
```

### Custom Skip Handlers

Handle permit acquisition failures with custom logic:

```java
public class AlertingSemaphoreHandler implements SemaphoreSkipHandler {

    @Override
    public Object handle(SemaphoreContext context) {
        alertService.sendAlert("No permit available: " + context.semaphoreKey());
        return "fallback-result";
    }
}

@DistributedSemaphore(
    key = "rate-limited",
    permits = 10,
    skipHandler = AlertingSemaphoreHandler.class
)
public String rateLimitedMethod() { }
```

Built-in handlers:
- `SemaphoreThrowExceptionHandler` (default) - Throws `SemaphoreNotAcquiredException`
- `SemaphoreReturnDefaultHandler` - Returns null/default values

### Semaphore Exception Handling

```java
try {
    myService.rateLimitedTask();
} catch (SemaphoreNotAcquiredException e) {
    log.warn("No permit available for semaphore: {}", e.getSemaphoreKey());
    // Handle accordingly
}
```

### `@DistributedSemaphore` Annotation Reference

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `key` | String | (required) | Semaphore key - literal string or SpEL expression wrapped in `#{...}` |
| `permits` | int | (required) | Maximum concurrent executions allowed |
| `mode` | LockAcquisitionMode | `SKIP_IMMEDIATELY` | How to acquire the permit |
| `leaseTime` | String | `""` (use config) | Permit auto-release time (e.g., "10m", "30s") |
| `waitTime` | String | `""` (use config) | Wait time for WAIT_AND_SKIP (e.g., "30s", "1m") |
| `skipHandler` | Class | `SemaphoreThrowExceptionHandler` | Handler for permit acquisition failures |
| `onLeaseExpired` | LeaseExpirationBehavior | `LOG_WARNING` | Behavior when execution exceeds lease time |

### How Semaphores Work

1. When a method with `@DistributedSemaphore` is called, the aspect intercepts it
2. It resolves the semaphore key (same SpEL rules as locks)
3. On first use, initializes the semaphore with the specified permit count in Redis
4. Attempts to acquire a permit from the Redis semaphore
5. If acquired: executes the method, then releases the permit
6. If not acquired: invokes the configured `skipHandler`

**Permit Consistency**: The first server to use a semaphore key sets its permit count. If another deployment uses the same key with a different permit count, a warning is logged but the existing count is preserved.

---

## Migration from v1.x to v2.0

### Configuration Changes

The configuration structure changed from flat to nested:

**Before (v1.x):**
```yaml
locksmith:
  lease-time: 10m
  wait-time: 60s
  key-prefix: "lock:"
```

**After (v2.0):**
```yaml
locksmith:
  lock:
    lease-time: 10m
    wait-time: 60s
    key-prefix: "lock:"
  semaphore:
    lease-time: 5m
    wait-time: 60s
    key-prefix: "semaphore:"
```

### New Exceptions

v2.0 adds semaphore-specific exceptions:
- `SemaphoreNotAcquiredException` - When permit cannot be acquired
- `SemaphoreLeaseExpiredException` - When execution exceeds permit lease time
- `SemaphoreConfigurationException` - When semaphore configuration is invalid

## License

Apache License 2.0
