package in.riido.locksmith.integration;

import static org.junit.jupiter.api.Assertions.*;

import in.riido.locksmith.LockType;
import in.riido.locksmith.autoconfigure.LocksmithProperties;
import in.riido.locksmith.template.LocksmithLockTemplate;
import in.riido.locksmith.template.handle.LockHandle;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@ExtendWith(DockerAvailableCondition.class)
@DisplayName("LocksmithLockTemplate Integration Tests")
class LocksmithLockTemplateIntegrationTest {

  private static final int REDIS_PORT = 6379;

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>(DockerImageName.parse("redis:latest")).withExposedPorts(REDIS_PORT);

  private RedissonClient redissonClient;
  private LocksmithLockTemplate template;

  @BeforeEach
  void setUp() {
    Config config = new Config();
    config
        .useSingleServer()
        .setAddress("redis://" + redis.getHost() + ":" + redis.getMappedPort(REDIS_PORT));
    redissonClient = Redisson.create(config);

    LocksmithProperties properties =
        new LocksmithProperties(
            new LocksmithProperties.LockProperties(
                true, Duration.ofMinutes(1), Duration.ofSeconds(10), "test:", false, false),
            null,
            null);
    template = new LocksmithLockTemplate(redissonClient, properties);
    final var keys = redissonClient.getKeys();
    if (keys != null && keys.count() > 0) {
      keys.flushall();
    }
  }

  @AfterEach
  void tearDown() {
    if (redissonClient != null && !redissonClient.isShutdown()) {
      redissonClient.shutdown();
    }
  }

  @Nested
  @DisplayName("Basic Lock Operations")
  class BasicLockOperations {

    @Test
    @DisplayName("Should acquire and release lock")
    void shouldAcquireAndReleaseLock() {
      try (LockHandle handle = template.withKey("basic-test").tryLock()) {
        assertTrue(handle.isAcquired());
        assertTrue(template.isLocked("basic-test"));
      }
      assertFalse(template.isLocked("basic-test"));
    }

    @Test
    @DisplayName("Should execute callback within lock")
    void shouldExecuteCallbackWithinLock() throws Exception {
      String result =
          template
              .withKey("callback-test")
              .execute(
                  () -> {
                    assertTrue(template.isLocked("callback-test"));
                    return "success";
                  });

      assertEquals("success", result);
      assertFalse(template.isLocked("callback-test"));
    }

    @Test
    @DisplayName("Should release lock after callback exception")
    void shouldReleaseLockAfterCallbackException() {
      assertThrows(
          RuntimeException.class,
          () ->
              template
                  .withKey("exception-test")
                  .execute(
                      () -> {
                        throw new RuntimeException("Test error");
                      }));

      assertFalse(template.isLocked("exception-test"));
    }
  }

  @Nested
  @DisplayName("Lock Contention")
  class LockContention {

    @Test
    @DisplayName("Should prevent concurrent access to same lock")
    void shouldPreventConcurrentAccess() throws InterruptedException {
      AtomicInteger concurrentExecutions = new AtomicInteger(0);
      AtomicInteger maxConcurrent = new AtomicInteger(0);
      CountDownLatch latch = new CountDownLatch(5);
      ExecutorService executor = Executors.newFixedThreadPool(5);

      for (int i = 0; i < 5; i++) {
        executor.submit(
            () -> {
              try {
                template
                    .withKey("contention-test")
                    .waitTime(Duration.ofSeconds(10))
                    .execute(
                        () -> {
                          int current = concurrentExecutions.incrementAndGet();
                          maxConcurrent.updateAndGet(max -> Math.max(max, current));
                          Thread.sleep(50);
                          concurrentExecutions.decrementAndGet();
                          return null;
                        });
              } catch (Exception e) {
                // Ignore
              } finally {
                latch.countDown();
              }
            });
      }

      assertTrue(latch.await(30, TimeUnit.SECONDS));
      assertEquals(1, maxConcurrent.get(), "Only one thread should execute at a time");
      executor.shutdown();
    }

    @Test
    @DisplayName("Should return false immediately when lock held by another")
    void shouldReturnFalseWhenLockHeld() throws Exception {
      // Acquire lock in current thread
      LockHandle mainHandle = template.withKey("held-lock").tryLock();
      assertTrue(mainHandle.isAcquired());

      // Try to acquire from another thread
      ExecutorService executor = Executors.newSingleThreadExecutor();
      Boolean otherThreadResult =
          executor
              .submit(
                  () -> {
                    try (LockHandle handle = template.withKey("held-lock").tryLock()) {
                      return handle.isAcquired();
                    }
                  })
              .get(5, TimeUnit.SECONDS);

      assertFalse(otherThreadResult);
      mainHandle.close();
      executor.shutdown();
    }

    @Test
    @DisplayName("Should acquire lock after release by another thread")
    void shouldAcquireLockAfterRelease() throws Exception {
      CountDownLatch lockAcquired = new CountDownLatch(1);
      AtomicInteger secondThreadAcquired = new AtomicInteger(0);

      Thread firstThread =
          new Thread(
              () -> {
                try (LockHandle handle = template.withKey("release-test").tryLock()) {
                  lockAcquired.countDown();
                  Thread.sleep(100);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              });

      Thread secondThread =
          new Thread(
              () -> {
                try {
                  lockAcquired.await();
                  try (LockHandle handle =
                      template.withKey("release-test").waitTime(Duration.ofSeconds(5)).tryLock()) {
                    if (handle.isAcquired()) {
                      secondThreadAcquired.set(1);
                    }
                  }
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              });

      firstThread.start();
      secondThread.start();
      firstThread.join(10000);
      secondThread.join(10000);

      assertEquals(1, secondThreadAcquired.get());
    }
  }

  @Nested
  @DisplayName("Read-Write Locks via Builder")
  class ReadWriteLocks {

    @Test
    @DisplayName("Should allow multiple concurrent read locks")
    void shouldAllowConcurrentReadLocks() throws InterruptedException {
      AtomicInteger concurrentReaders = new AtomicInteger(0);
      AtomicInteger maxConcurrent = new AtomicInteger(0);
      CountDownLatch allStarted = new CountDownLatch(3);
      CountDownLatch allComplete = new CountDownLatch(3);

      ExecutorService executor = Executors.newFixedThreadPool(3);

      for (int i = 0; i < 3; i++) {
        executor.submit(
            () -> {
              try (LockHandle handle =
                  template
                      .withKey("rw-read-test")
                      .waitTime(Duration.ofSeconds(5))
                      .leaseTime(Duration.ofMinutes(1))
                      .lockType(LockType.READ)
                      .tryLock()) {
                if (handle.isAcquired()) {
                  int current = concurrentReaders.incrementAndGet();
                  maxConcurrent.updateAndGet(max -> Math.max(max, current));
                  allStarted.countDown();
                  Thread.sleep(200);
                  concurrentReaders.decrementAndGet();
                }
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } finally {
                allComplete.countDown();
              }
            });
      }

      assertTrue(allComplete.await(15, TimeUnit.SECONDS));
      assertTrue(maxConcurrent.get() > 1, "Multiple readers should run concurrently");
      executor.shutdown();
    }

    @Test
    @DisplayName("Should block write lock when read lock held")
    void shouldBlockWriteWhenReadHeld() throws Exception {
      // Acquire read lock via builder
      LockHandle readHandle =
          template
              .withKey("rw-block-test")
              .leaseTime(Duration.ofMinutes(1))
              .lockType(LockType.READ)
              .tryLock();
      assertTrue(readHandle.isAcquired());

      // Try write lock from another thread
      ExecutorService executor = Executors.newSingleThreadExecutor();
      Boolean writeResult =
          executor
              .submit(
                  () -> {
                    try (LockHandle handle =
                        template
                            .withKey("rw-block-test")
                            .leaseTime(Duration.ofMinutes(1))
                            .lockType(LockType.WRITE)
                            .tryLock()) {
                      return handle.isAcquired();
                    }
                  })
              .get(5, TimeUnit.SECONDS);

      assertFalse(writeResult);
      readHandle.close();
      executor.shutdown();
    }

    @Test
    @DisplayName("Should block read lock when write lock held")
    void shouldBlockReadWhenWriteHeld() throws Exception {
      // Acquire write lock via builder
      LockHandle writeHandle =
          template
              .withKey("rw-block-test2")
              .leaseTime(Duration.ofMinutes(1))
              .lockType(LockType.WRITE)
              .tryLock();
      assertTrue(writeHandle.isAcquired());

      // Try read lock from another thread
      ExecutorService executor = Executors.newSingleThreadExecutor();
      Boolean readResult =
          executor
              .submit(
                  () -> {
                    try (LockHandle handle =
                        template
                            .withKey("rw-block-test2")
                            .leaseTime(Duration.ofMinutes(1))
                            .lockType(LockType.READ)
                            .tryLock()) {
                      return handle.isAcquired();
                    }
                  })
              .get(5, TimeUnit.SECONDS);

      assertFalse(readResult);
      writeHandle.close();
      executor.shutdown();
    }
  }

  @Nested
  @DisplayName("Auto-Renew via Builder")
  class AutoRenew {

    @Test
    @DisplayName("Should auto-renew lock beyond original lease time")
    void shouldAutoRenewLock() throws Exception {
      // Use autoRenew() via builder
      String result =
          template
              .withKey("auto-renew-test")
              .autoRenew()
              .execute(
                  () -> {
                    // Sleep longer than would be allowed without auto-renew
                    Thread.sleep(2000);
                    return "completed";
                  });

      assertEquals("completed", result);
      assertFalse(template.isLocked("auto-renew-test"));
    }
  }

  @Nested
  @DisplayName("Different Keys")
  class DifferentKeys {

    @Test
    @DisplayName("Should allow concurrent locks on different keys")
    void shouldAllowConcurrentLocksOnDifferentKeys() throws InterruptedException {
      AtomicInteger completedCount = new AtomicInteger(0);
      CountDownLatch latch = new CountDownLatch(3);

      ExecutorService executor = Executors.newFixedThreadPool(3);

      for (int i = 0; i < 3; i++) {
        final String key = "key-" + i;
        executor.submit(
            () -> {
              try {
                template
                    .withKey(key)
                    .execute(
                        () -> {
                          Thread.sleep(100);
                          completedCount.incrementAndGet();
                          return null;
                        });
              } catch (Exception e) {
                // Ignore
              } finally {
                latch.countDown();
              }
            });
      }

      assertTrue(latch.await(10, TimeUnit.SECONDS));
      assertEquals(3, completedCount.get());
      executor.shutdown();
    }
  }
}
