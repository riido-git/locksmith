# Locksmith

Redis-based distributed locks and semaphores for Spring Boot, built on Redisson.

## Overview

Locksmith coordinates work across the instances of a Spring Boot application through Redis. It
offers two primitives:

| Primitive | Guarantees | Typical use |
|---|---|---|
| Lock | One holder at a time per key (or many readers, one writer) | a scheduled job, one order processed once |
| Semaphore | At most N holders at a time per key | a limit on concurrent calls to a slow service |

Each primitive has two entry points that share one implementation:

- an annotation on a Spring bean method: `@DistributedLock`, `@DistributedSemaphore`;
- a programmatic API: the `LockOperations` and `SemaphoreOperations` beans.

## Requirements

- Java 17 or later.
- Spring Boot 4.1.x.
- Redisson 4.x, supplied by you as a `RedissonClient` bean, for example through
  `redisson-spring-boot-starter`. Locksmith 4.0.0 is built and tested against Redisson 4.7.0.
- A Redis server.

Locksmith declares Spring and Redisson as `provided` dependencies. It does not bring them onto your
classpath; your application does. Micrometer is optional. AspectJ is not needed.

## Installation

Maven:

```xml
<dependency>
    <groupId>in.riido</groupId>
    <artifactId>locksmith-spring-boot-starter</artifactId>
    <version>4.0.0</version>
</dependency>

<!-- Supplies the RedissonClient bean. Any other way of defining that bean works too. -->
<dependency>
    <groupId>org.redisson</groupId>
    <artifactId>redisson-spring-boot-starter</artifactId>
    <version>4.7.0</version>
</dependency>
```

Gradle:

```groovy
implementation 'in.riido:locksmith-spring-boot-starter:4.0.0'
implementation 'org.redisson:redisson-spring-boot-starter:4.7.0'
```

If you define the client yourself instead of using the Redisson starter, depend on
`org.redisson:redisson` and declare the bean:

```java
@Configuration
public class RedisConfig {

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://localhost:6379");
        return Redisson.create(config);
    }
}
```

Locksmith registers itself only when a `RedissonClient` bean exists. Without one it registers
nothing and logs nothing.

## Quick start

```java
@Service
public class OrderService {

    @DistributedLock(key = "order:#{#orderId}")
    public void process(String orderId) {
        // runs for one orderId at a time, across all instances
    }
}
```

A call with `orderId = "42"` locks the Redis key `locksmith:lock:order:42`. Locksmith tries once. If
another thread or instance holds that lock, the method does not run and the call throws
`LockNotAcquiredException` with the message
`Lock [locksmith:lock:order:42] not acquired within PT0S`. To wait, set `waitTime`. To return
quietly instead of throwing, set `onFailure = OnFailure.SKIP`; see
[Failure handling](#failure-handling).

The lock is released when the method returns or throws.

## Locks

```java
@DistributedLock(
    key = "order:#{#orderId}",
    type = LockType.REENTRANT,
    waitTime = "5s",
    leaseTime = "",
    onFailure = OnFailure.THROW)
public void process(String orderId) { ... }
```

| Attribute | Default | Meaning |
|---|---|---|
| `key` | required | key template; see [Key templates](#key-templates) |
| `type` | `REENTRANT` | `REENTRANT`, `READ` or `WRITE` |
| `waitTime` | `""` | how long to wait for the lock; blank or zero means try once and give up |
| `leaseTime` | `""` | blank: the lock is renewed while held; a value: a fixed lease, not renewed |
| `onFailure` | `THROW` | `THROW`, `SKIP` or `HANDLER`; see [Failure handling](#failure-handling) |
| `handler` | not set | the `LockFailureHandler` bean type, required with `HANDLER` only |

Durations are written as `5s`, `500ms`, `2m` or in ISO-8601 form such as `PT5S`. `waitTime` must not
be negative. `leaseTime`, when set, must be at least one millisecond.

**Lock types.** `REENTRANT` is an exclusive lock that the holding thread may take again, for
example when an annotated method calls an annotated method of another bean with the same key.
`READ` is shared: any number of readers hold it while no writer does. `WRITE` excludes readers and other writers. A `READ` and a `WRITE`
lock with the same key share one Redis key, `locksmith:lock:<key>`, which is what makes them exclude
each other. Do not use `REENTRANT` and `READ`/`WRITE` on the same key; that combination is not
supported.

**Blank `leaseTime`: renewal.** The lock has no fixed end. Redisson renews it in the background for
as long as the method runs, and releases it when the method ends. If the JVM dies, renewal stops and
the key expires after Redisson's watchdog timeout (30 seconds by default, `lockWatchdogTimeout` in
the Redisson configuration). This is the right choice for almost every method.

**Explicit `leaseTime`: a fixed lease.** The lock expires that long after it was acquired, whether
the method has finished or not, and it is never renewed. If the method outruns the lease, another
instance may take the lock while the method is still running. Locksmith cannot stop that. It
detects it at release and logs one WARN, for example:

```
Lock [locksmith:lock:order:42] was no longer held at release after 2004ms (fixed lease 1000ms);
another instance may have run concurrently: ...
```

The method's result is returned as usual; the WARN is the only signal.

## Semaphores

```java
@DistributedSemaphore(
    key = "reports",
    permits = "${reports.max-concurrent:5}",
    waitTime = "2s",
    leaseTime = "10m")
public Report build(String id) { ... }
```

| Attribute | Default | Meaning |
|---|---|---|
| `key` | required | key template; see [Key templates](#key-templates) |
| `permits` | required | the number of permits, as a string so a `${...}` placeholder works; must resolve to an integer greater than zero |
| `waitTime` | `""` | how long to wait for a permit; blank or zero means try once and give up |
| `leaseTime` | `""` | the lease of a permit; blank means `locksmith.semaphore.lease-time` (5 minutes by default) |
| `onFailure` | `THROW` | `THROW`, `SKIP` or `HANDLER` |
| `handler` | not set | the `SemaphoreFailureHandler` bean type, required with `HANDLER` only |

The semaphore lives at the Redis key `locksmith:semaphore:<key>`. Each call holds one permit.

**The lease is always fixed.** Redisson permits have no renewal. A permit expires `leaseTime` after
it was acquired, even if the method is still running; set it longer than the slowest call. When a
method outruns it, the release logs one WARN (`Permit [...] was no longer held at release ...`) and
the result is returned as usual. `leaseTime`, when set, must be at least one millisecond.

**Your code owns the permit count.** Redis stores the count, and the annotation (or the
`permits(int)` call) states it. The first acquire of a key in a JVM, and the first acquire with a
different count, writes the count to Redis:

- a semaphore that does not exist yet is created with that count;
- a semaphore that has another count is changed to it, and Locksmith logs one INFO line:
  `Semaphore [locksmith:semaphore:reports] permits changed from 5 to 8`.

The last writer wins. Each JVM writes its count once per key, and again only when its own count
changes. During a rolling deploy that changes the count, an old instance that acquires the key for
the first time after a new one did puts the old count back, and it stays there until an instance
writes again, for example a new instance after its start. Two call sites in one JVM that use one
key with different counts overwrite each other at every switch, with one INFO line each time. Give
each count its own key, or read it from one property. If Redis loses the semaphore key, for example
after a restart without persistence, the next acquire that finds no free permit re-creates it with
its count.

## Async methods

A method that returns `CompletableFuture`, `CompletionStage` or another `CompletionStage` type holds
its lock and permit until the future it returns completes, not until the method returns:

```java
@DistributedLock(key = "order:#{#orderId}", waitTime = "5s", leaseTime = "10m")
public CompletableFuture<Receipt> process(String orderId) {
    return client.submit(orderId).thenApply(Receipt::from);
}
```

- The permit and then the lock are acquired on the calling thread before the method runs, as for any
  other method. `THROW` throws there, before any future exists.
- The lock, then the permit, is released when the returned future completes: normally,
  exceptionally, or because the caller cancelled it. The release does not block and works on any
  thread, including a Redisson I/O thread.
- The caller gets the method's own future, so `cancel()` reaches it.
- If the method throws, or returns `null`, both are released before the call returns, as for any
  other method.
- `SKIP` returns a future already completed with `null`. `HANDLER` returns whatever the handler
  returns.
- The lock is owned by a generated id, not by a thread, so it is not reentrant: a nested
  `@DistributedLock` on the same key inside the async work is not acquired, even on the same thread.

With Spring's `@Async`, the `@Async` interceptor runs first, so Locksmith runs on the worker thread
and the lock covers the whole method body. That is why a plain `Future` is accepted on an `@Async`
method (see [Limitations](#limitations)).

## Programmatic API

Locksmith registers two beans, `in.riido.locksmith.lock.LockOperations` and
`in.riido.locksmith.semaphore.SemaphoreOperations`. Inject them like any other bean.

```java
@Service
public class ReportService {

    private final LockOperations locks;
    private final SemaphoreOperations semaphores;

    public ReportService(LockOperations locks, SemaphoreOperations semaphores) {
        this.locks = locks;
        this.semaphores = semaphores;
    }

    public void rebuild(String id) {
        try (LockHandle lock = locks.key("report:" + id)
                .type(LockType.WRITE)
                .waitTime(Duration.ofSeconds(5))
                .acquire()) {
            if (!lock.acquired()) {
                return; // someone else is rebuilding it
            }
            // critical section
        }
    }

    public void export() {
        try (PermitHandle permit = semaphores.key("reports")
                .permits(5)
                .waitTime(Duration.ofSeconds(2))
                .acquire()) {
            if (permit.acquired()) {
                // at most five of these run at once, across all instances
            }
        }
    }
}
```

`LockOperations`:

| Call | Meaning |
|---|---|
| `key(String)` | starts an acquire; the key has no prefix, Locksmith adds `<prefix>lock:` |
| `.type(LockType)` | defaults to `REENTRANT` |
| `.waitTime(Duration)` | defaults to `Duration.ZERO`, try once; negative throws `IllegalArgumentException` |
| `.leaseTime(Duration)` | a fixed lease, not renewed; not calling it means renewal while held; below one millisecond throws `IllegalArgumentException` |
| `.acquire()` | tries to acquire and returns a `LockHandle` |
| `isLocked(String key, LockType type)` | whether any thread on any instance holds the lock |

`SemaphoreOperations`:

| Call | Meaning |
|---|---|
| `key(String)` | starts an acquire; Locksmith adds `<prefix>semaphore:` |
| `.permits(int)` | the permit count; required, at least one, otherwise `acquire()` throws `LocksmithConfigurationException` |
| `.waitTime(Duration)` | defaults to `Duration.ZERO`, try once; negative throws `IllegalArgumentException` |
| `.leaseTime(Duration)` | defaults to `locksmith.semaphore.lease-time`; below one millisecond throws `IllegalArgumentException` |
| `.acquire()` | tries to acquire one permit and returns a `PermitHandle` |
| `availablePermits(String key)` | the number of free permits right now |

Handles:

| Call | `LockHandle` | `PermitHandle` |
|---|---|---|
| `acquired()` | whether the lock was acquired | whether a permit was acquired |
| `key()` | the full Redis key, for example `locksmith:lock:report:7` | the full Redis key |
| `permitId()` | - | the Redisson permit id, or `null` when not acquired |
| `close()` | releases the lock if acquired | releases the permit if acquired |

Rules:

- `acquire()` never throws for "not acquired". Check `acquired()`.
- If the thread is interrupted while waiting, `acquire()` restores the interrupt flag and returns an
  unacquired handle.
- `close()` never throws. Closing an unacquired handle, or closing twice, does nothing. A failed
  release is logged as a WARN and the key expires on its own.
- Use try-with-resources so the handle is always closed.
- Close a `LockHandle` on the thread that acquired it. See [Limitations](#limitations).
- Redisson exceptions, for example when Redis is unreachable, propagate unchanged from `acquire()`,
  `isLocked` and `availablePermits`. A Redis error is never reported as "not acquired".

## Key templates

The `key` attribute of both annotations is a Spring expression template: literal text with
`#{...}` islands. Only the islands are evaluated.

| Template | Called with | Resolved key |
|---|---|---|
| `"scheduler:cleanup"` | anything | `scheduler:cleanup` |
| `"order#42"` | anything | `order#42` (a `#` outside `#{...}` is plain text) |
| `"user:#{#userId}"` | `userId = "u7"` | `user:u7` |
| `"#{'user-' + #id}"` | `id = 7` | `user-7` |
| `"order:#{#order.id}"` | an `order` whose `id` is 42 | `order:42` |
| `"tenant:#{#p0}:#{#a1}"` | `("t1", "x")` | `tenant:t1:x` |
| `"batch:#{#ids.?[#this > 10].size()}"` | `ids = [5, 11, 12]` | `batch:2` |

Variables inside an island:

- a method parameter by name, such as `#userId`;
- a parameter by position, `#p0`, `#p1`, ... or `#a0`, `#a1`, ...;
- `#this`, the current element inside a selection or projection such as `.?[...]` or `.![...]`.

There is no root object and there are no bean references: `#root` and `@myBean` are not available.

Every template is checked at startup. A variable that is not a parameter of the method fails the
startup with a message that ends in `compile with -parameters or use #p0`. Parameter names are only
available when your code is compiled with `-parameters`; `spring-boot-starter-parent` and Spring
Boot's Gradle plugin set it, a plain `javac` build does not. Without it, use `#p0` or `#a0`.

A template that resolves to `null` or a blank string at call time throws
`LocksmithConfigurationException`, and the method does not run. So does a longer template
when any one of its islands resolves to `null` or a blank string: `order:#{#id}` with `id` null or
`""` throws instead of locking `order:`.

The resolved key is prefixed before it reaches Redis:

- locks: `<prefix>lock:<resolved key>`, for example `locksmith:lock:user:u7`;
- semaphores: `<prefix>semaphore:<resolved key>`.

`<prefix>` is `locksmith.key-prefix`, `locksmith:` by default.

## Failure handling

`onFailure` decides what an annotated method does when its lock or permit is not acquired within
`waitTime`, including when the waiting thread is interrupted.

**`THROW` (default).** The method does not run. The call throws:

| Exception | Fields | Message |
|---|---|---|
| `in.riido.locksmith.lock.LockNotAcquiredException` | `key()`, `waitTime()` | `Lock [locksmith:lock:order:42] not acquired within PT5S` |
| `in.riido.locksmith.semaphore.SemaphoreNotAcquiredException` | `key()`, `permits()`, `waitTime()` | `Semaphore [locksmith:semaphore:reports] permit not acquired within PT2S (permits 5)` |

`key()` is the full Redis key. `waitTime()` is the configured wait time. Both extend
`in.riido.locksmith.LocksmithException`, which is unchecked, as is
`LocksmithConfigurationException`, the third subclass.

**`SKIP`.** The method does not run and returns the default of its return type:

| Return type | Value |
|---|---|
| `void` | nothing |
| `Optional` | `Optional.empty()` |
| `CompletionStage`, `CompletableFuture` | a future already completed with `null` |
| `boolean`, `Boolean` | `false` |
| `byte`, `short`, `int`, `long`, `float`, `double`, `char` and their boxes | zero of that type |
| any other type | `null` |

**`HANDLER`.** The method does not run. Locksmith calls the handler bean and returns its value
as-is, so return something the method's return type accepts. Anything the handler throws propagates.

```java
@Component
public class BusyOrderHandler implements LockFailureHandler {

    @Override
    public Object onFailure(LockFailureContext context) {
        return OrderResult.busy(context.key());
    }
}

@DistributedLock(
    key = "order:#{#orderId}",
    onFailure = OnFailure.HANDLER,
    handler = BusyOrderHandler.class)
public OrderResult process(String orderId) { ... }
```

`handler` names a type, and exactly one bean of that type must exist in the application context;
Locksmith looks it up on the first failure and never instantiates it itself. `handler` is required
with `HANDLER` and not allowed with the other policies. The handler receives:

| Record | Components |
|---|---|
| `LockFailureContext` | `key` (full Redis key), `method`, `args`, `waitTime` |
| `SemaphoreFailureContext` | `key` (full Redis key), `permits`, `method`, `args`, `waitTime` |

For a semaphore, implement `SemaphoreFailureHandler` the same way.

**Both annotations on one method.** Locksmith takes the permit first, then the lock, and releases
them in reverse order: lock first, then permit. So a caller never holds the lock while it waits in
line for a permit. If no permit is acquired, the semaphore's `onFailure` applies. If the permit is
acquired but the lock is not, the permit is released first, and then the lock's `onFailure`
applies; a lock handler therefore runs without holding the permit.

A Redis error is never a "not acquired" outcome. The Redisson exception propagates unchanged from the
annotated method, and `onFailure` does not apply.

## Configuration

```yaml
locksmith:
  enabled: true              # default true
  key-prefix: "locksmith:"   # default "locksmith:"
  semaphore:
    lease-time: 5m           # default 5m
```

| Property | Default | Meaning |
|---|---|---|
| `locksmith.enabled` | `true` | `false` switches Locksmith off |
| `locksmith.key-prefix` | `locksmith:` | prefix of every Redis key; blank falls back to the default |
| `locksmith.semaphore.lease-time` | `5m` | lease of a permit when `leaseTime` is blank or not set; must be at least one millisecond |

There are no other properties.

**Startup line.** When Locksmith is active it logs one INFO line, for example:

```
Locksmith enabled: key-prefix [locksmith:], semaphore lease-time PT5M, Spring Boot 4.1.1, Redisson 4.7.0
```

**`locksmith.enabled=false`.** Locksmith registers nothing and logs one WARN:
`Locksmith is disabled: annotated methods run without any coordination`. Annotated methods then run
as plain methods, with no lock and no permit. A bean that injects `LockOperations` or
`SemaphoreOperations` fails the startup with a missing bean; this is intended.

**Startup validation.** Locksmith checks every `@DistributedLock` and `@DistributedSemaphore` when
its bean is created: the key is not blank and parses, every template variable exists, the durations
parse and are in range, `permits` resolves to a positive integer, and `onFailure` and `handler`
agree, with exactly one handler bean. A misconfiguration throws `LocksmithConfigurationException`
naming the class, the method and the value, and the application context refresh fails, so the
application does not start. For example:

```
@DistributedLock on com.example.OrderService.process: waitTime [5x] is not a duration such as 5s or PT5S
```

A `locksmith.semaphore.lease-time` below one millisecond, including zero and negative values, also
fails the startup.

**Logging.** Locksmith logs one DEBUG line per release and per failed acquire, under loggers in
`in.riido.locksmith`. To see them:

```yaml
logging:
  level:
    in.riido.locksmith: DEBUG
```

WARN lines appear only for events an operator should see: a lease that ran out, a failed release,
Locksmith disabled.

## Metrics

When a Micrometer `MeterRegistry` bean exists, for example through `spring-boot-starter-actuator`,
Locksmith records two timers. Without a registry it records nothing. There is no property.

| Meter | Tags | Records |
|---|---|---|
| `locksmith.acquire` | `primitive` = `lock` or `semaphore`; `outcome` = `acquired`, `skipped` or `interrupted` | the time spent waiting, once per acquire attempt |
| `locksmith.held` | `primitive` = `lock` or `semaphore` | the time from acquire to release, once per successful release |

`skipped` means the wait time ran out. The key is not a tag, so the number of meters stays fixed.

## Limitations

- **Renewal during a Redis outage.** A lock without `leaseTime` is renewed by Redisson in the
  background. If Redis becomes unreachable while the method runs, renewal fails, and the key expires
  after the watchdog timeout (30 seconds by default) while the method keeps running. Another instance
  can then take the lock. Locksmith cannot detect this and the method is not told.
- **Thread affinity.** A `LockHandle` must be closed on the thread that acquired it, because Redisson
  ties lock ownership to the acquiring thread. This is not enforced: closing it on another thread
  fails the release, which is logged as a WARN, and the lock stays until it expires. Permits are not
  tied to a thread; a `PermitHandle` can be closed anywhere.
- **Self-invocation.** The annotations work only on calls that go through the Spring proxy. A call on
  `this` from inside the same bean bypasses the proxy and runs without a lock or permit, exactly as
  with `@Transactional`.
- **Interface proxies.** Locksmith registers Spring's auto-proxy creator itself when the context has
  none, so the annotations work with or without `spring-boot-starter-aop` and AspectJ, and whatever
  `spring.aop.auto` and `spring.aop.proxy-target-class` say. With interface proxies
  (`spring.aop.proxy-target-class=false` and no AspectJ), a bean that implements an interface is
  reachable only through its interfaces. Put the annotation on the interface method, or on the
  method that implements an interface method. A method that is on no interface is not intercepted in
  that mode.
- **Cancelling an async method.** Cancelling the returned future releases the lock and permit at
  once, even if the work behind the future keeps running. Another instance can then run the same
  work at the same time.
- **An async method whose future never completes.** Its lock is held until the JVM stops: without
  `leaseTime` the watchdog keeps renewing it. Set `leaseTime` on async methods where a future might
  never complete. A permit always has a lease, so it expires on its own.
- **No reentrancy on the async path.** A lock taken for a method that returns a `CompletionStage`
  is not owned by any thread, so a nested same-key `@DistributedLock` is not acquired, even on the
  same thread.
- **Return types that are rejected.** Startup fails with a `LocksmithConfigurationException` for an
  annotated method that returns a reactive type (`Mono`, `Flux`, any `org.reactivestreams.Publisher`
  or `java.util.concurrent.Flow.Publisher`), for a Kotlin `suspend` function, and for a
  `java.util.concurrent.Future` that is not a `CompletionStage`, such as `ForkJoinTask`. Locksmith
  cannot see when those finish. The exception is a `Future` on a method that is `@Async`, or in a
  class that is: there the lock already covers the whole body on the worker thread. `SKIP` is also
  rejected on a `CompletionStage` type that a `CompletableFuture` cannot be assigned to, such as a
  `CompletableFuture` subclass; declare `CompletableFuture` or `CompletionStage`, or use `HANDLER`.
- **Handlers in a parent context.** The startup check counts handler beans in the application
  context that holds the annotated bean only. A handler bean defined only in a parent context is not
  counted, so the startup fails with `found 0`. Define the handler in the same context.

## License

See `LICENSE`.
