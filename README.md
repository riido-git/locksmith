# Locksmith

Redis-based distributed locks and semaphores for Spring Boot, built on Redisson.

## Overview

Locksmith coordinates work across the instances of a Spring Boot application through Redis. It
offers two primitives:

| Primitive | Guarantees | Typical use |
|---|---|---|
| Lock | One holder at a time per key (or many readers, one writer) | a scheduled job that must not overlap itself, one order handled by one instance at a time |
| Semaphore | At most N holders at a time per key | a limit on concurrent calls to a slow service |

A key names what is coordinated, for example one order or one job.

Each primitive has two entry points that share one implementation:

- an annotation on a Spring bean method: `@DistributedLock`, `@DistributedSemaphore`;
- a programmatic API: the `LockOperations` and `SemaphoreOperations` beans.

## Requirements

- Java 17 or later.
- Spring Boot 4.1.x.
- Redisson 4.x, supplied by you as a `RedissonClient` bean, for example through
  `redisson-spring-boot-starter`. Locksmith 4.0.0 is built and tested against Redisson 4.8.0.
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
    <version>4.8.0</version>
</dependency>
```

Gradle:

```groovy
implementation 'in.riido:locksmith-spring-boot-starter:4.0.0'
implementation 'org.redisson:redisson-spring-boot-starter:4.8.0'
```

To declare the `RedissonClient` bean yourself instead, see
[Configuration](https://github.com/riido-git/locksmith/wiki/Configuration#your-own-redissonclient-bean).

Upgrading from 3.x: the migration table is in [CHANGELOG.md](CHANGELOG.md).

## Quick start

A lock:

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
[Failure handling](https://github.com/riido-git/locksmith/wiki/Failure-handling).

The lock is released when the method returns or throws.

A semaphore:

```java
@Service
public class ReportService {

    @DistributedSemaphore(key = "reports", permits = "5")
    public void export() {
        // at most five of these run at once, across all instances
    }
}
```

Each call holds one of five permits while it runs. The semaphore lives at the Redis key
`locksmith:semaphore:reports`. Locksmith tries once. If all five are held, the method does not run
and the call throws `SemaphoreNotAcquiredException`.

The permit is released when the method returns or throws. It also expires 5 minutes after it was
acquired, by default, even if the method is still running; see
[Semaphores](https://github.com/riido-git/locksmith/wiki/Semaphores).

## Documentation

The [wiki](https://github.com/riido-git/locksmith/wiki) covers everything else, one topic per page:

- [Locks](https://github.com/riido-git/locksmith/wiki/Locks): `@DistributedLock`, its attributes,
  the lock types and how a lock's lease is renewed or fixed.
- [Semaphores](https://github.com/riido-git/locksmith/wiki/Semaphores): `@DistributedSemaphore`, its
  attributes, permit leases and how the permit count is kept in Redis.
- [Key templates](https://github.com/riido-git/locksmith/wiki/Key-templates): how a `key` such as
  `order:#{#orderId}` is resolved to a Redis key.
- [Failure handling](https://github.com/riido-git/locksmith/wiki/Failure-handling): throw, skip or
  call a handler when a lock or permit is not acquired.
- [With other Spring annotations](https://github.com/riido-git/locksmith/wiki/With-other-Spring-annotations):
  how Locksmith works with Spring Security, `@Transactional`, `@Cacheable`, `@Async` and retries,
  including methods that return a future.
- [Programmatic API](https://github.com/riido-git/locksmith/wiki/Programmatic-API): the
  `LockOperations` and `SemaphoreOperations` beans, for locking in code instead of with an
  annotation.
- [Configuration](https://github.com/riido-git/locksmith/wiki/Configuration): the properties, your
  own `RedissonClient` bean, startup checks and what Locksmith logs.
- [Metrics](https://github.com/riido-git/locksmith/wiki/Metrics): the two Micrometer timers Locksmith
  records.
- [Limitations](https://github.com/riido-git/locksmith/wiki/Limitations): what Locksmith does not
  guarantee, and what to do about it.

## License

Apache License 2.0; see [LICENSE](LICENSE).
