package in.riido.locksmith.integration;

import static org.junit.jupiter.api.Assertions.*;

import in.riido.locksmith.autoconfigure.LocksmithProperties;
import in.riido.locksmith.exception.SemaphoreConfigurationException;
import in.riido.locksmith.template.LocksmithSemaphoreTemplate;
import in.riido.locksmith.template.PermitHandle;
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
@DisplayName("LocksmithSemaphoreTemplate Integration Tests")
class LocksmithSemaphoreTemplateIntegrationTest {

  private static final int REDIS_PORT = 6379;

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>(DockerImageName.parse("redis:latest")).withExposedPorts(REDIS_PORT);

  private RedissonClient redissonClient;
  private LocksmithSemaphoreTemplate template;

  @BeforeEach
  void setUp() {
    Config config = new Config();
    config
        .useSingleServer()
        .setAddress("redis://" + redis.getHost() + ":" + redis.getMappedPort(REDIS_PORT));
    redissonClient = Redisson.create(config);

    LocksmithProperties properties =
        new LocksmithProperties(
            null,
            new LocksmithProperties.SemaphoreProperties(
                true, Duration.ofMinutes(1), Duration.ofSeconds(10), "test:", false, false),
            null);
    template = new LocksmithSemaphoreTemplate(redissonClient, properties);
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
  @DisplayName("Basic Permit Operations")
  class BasicPermitOperations {

    @Test
    @DisplayName("Should acquire and release permit")
    void shouldAcquireAndReleasePermit() {
      try (PermitHandle handle = template.withKey("basic-test").permits(5).tryAcquire()) {
        assertTrue(handle.isAcquired());
        assertNotNull(handle.permitId());
      }
    }

    @Test
    @DisplayName("Should execute callback while holding permit")
    void shouldExecuteCallbackWithPermit() throws Exception {
      AtomicInteger executed = new AtomicInteger(0);

      String result =
          template
              .withKey("callback-test")
              .permits(5)
              .execute(
                  () -> {
                    executed.set(1);
                    return "success";
                  });

      assertEquals("success", result);
      assertEquals(1, executed.get());
    }

    @Test
    @DisplayName("Should release permit after callback exception")
    void shouldReleasePermitAfterCallbackException() {
      assertThrows(
          RuntimeException.class,
          () ->
              template
                  .withKey("exception-test")
                  .permits(5)
                  .execute(
                      () -> {
                        throw new RuntimeException("Test error");
                      }));

      // Should be able to acquire permit again
      try (PermitHandle handle = template.withKey("exception-test").permits(5).tryAcquire()) {
        assertTrue(handle.isAcquired());
      }
    }
  }

  @Nested
  @DisplayName("Permit Limiting")
  class PermitLimiting {

    @Test
    @DisplayName("Should limit concurrent access to number of permits")
    void shouldLimitConcurrentAccess() throws InterruptedException {
      int maxPermits = 3;
      AtomicInteger concurrentExecutions = new AtomicInteger(0);
      AtomicInteger maxConcurrent = new AtomicInteger(0);
      CountDownLatch latch = new CountDownLatch(10);
      ExecutorService executor = Executors.newFixedThreadPool(10);

      for (int i = 0; i < 10; i++) {
        executor.submit(
            () -> {
              try {
                template
                    .withKey("limit-test")
                    .permits(maxPermits)
                    .waitTime(Duration.ofSeconds(10))
                    .execute(
                        () -> {
                          int current = concurrentExecutions.incrementAndGet();
                          maxConcurrent.updateAndGet(max -> Math.max(max, current));
                          Thread.sleep(100);
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
      assertTrue(
          maxConcurrent.get() <= maxPermits,
          "Max concurrent should not exceed " + maxPermits + ", was: " + maxConcurrent.get());
      assertTrue(maxConcurrent.get() >= 1, "At least one execution should have occurred");
      executor.shutdown();
    }

    @Test
    @DisplayName("Should not acquire when all permits are held")
    void shouldNotAcquireWhenAllPermitsHeld() throws Exception {
      int maxPermits = 2;
      CountDownLatch permitsAcquired = new CountDownLatch(maxPermits);
      CountDownLatch testComplete = new CountDownLatch(1);

      ExecutorService executor = Executors.newFixedThreadPool(maxPermits);

      // Acquire all permits
      for (int i = 0; i < maxPermits; i++) {
        executor.submit(
            () -> {
              try {
                PermitHandle handle =
                    template.withKey("full-test").permits(maxPermits).tryAcquire();
                if (handle.isAcquired()) {
                  permitsAcquired.countDown();
                  testComplete.await(10, TimeUnit.SECONDS);
                  handle.close();
                }
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            });
      }

      assertTrue(permitsAcquired.await(5, TimeUnit.SECONDS));

      // Try to acquire when all permits are held
      try (PermitHandle handle = template.withKey("full-test").permits(maxPermits).tryAcquire()) {
        assertFalse(handle.isAcquired());
      }

      testComplete.countDown();
      executor.shutdown();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  @Nested
  @DisplayName("Wait Time via Builder")
  class WaitTime {

    @Test
    @DisplayName("Should wait and acquire permit when one becomes available")
    void shouldWaitAndAcquirePermit() throws InterruptedException {
      int maxPermits = 1;
      CountDownLatch firstPermitAcquired = new CountDownLatch(1);
      CountDownLatch secondPermitAcquired = new CountDownLatch(1);

      Thread firstThread =
          new Thread(
              () -> {
                try (PermitHandle handle =
                    template.withKey("wait-test").permits(maxPermits).tryAcquire()) {
                  if (handle.isAcquired()) {
                    firstPermitAcquired.countDown();
                    Thread.sleep(200);
                  }
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              });

      Thread secondThread =
          new Thread(
              () -> {
                try {
                  firstPermitAcquired.await();
                  try (PermitHandle handle =
                      template
                          .withKey("wait-test")
                          .permits(maxPermits)
                          .waitTime(Duration.ofSeconds(5))
                          .tryAcquire()) {
                    if (handle.isAcquired()) {
                      secondPermitAcquired.countDown();
                    }
                  }
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              });

      firstThread.start();
      secondThread.start();

      assertTrue(secondPermitAcquired.await(10, TimeUnit.SECONDS));

      firstThread.join(5000);
      secondThread.join(5000);
    }
  }

  @Nested
  @DisplayName("Different Keys")
  class DifferentKeys {

    @Test
    @DisplayName("Should allow permits on different keys independently")
    void shouldAllowPermitsOnDifferentKeys() throws InterruptedException {
      AtomicInteger completedCount = new AtomicInteger(0);
      CountDownLatch latch = new CountDownLatch(5);

      ExecutorService executor = Executors.newFixedThreadPool(5);

      for (int i = 0; i < 5; i++) {
        final String key = "key-" + i;
        executor.submit(
            () -> {
              try {
                template
                    .withKey(key)
                    .permits(1)
                    .execute(
                        () -> {
                          Thread.sleep(50);
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
      assertEquals(5, completedCount.get());
      executor.shutdown();
    }
  }

  @Nested
  @DisplayName("Permit Validation")
  class PermitValidation {

    @Test
    @DisplayName("Should throw exception for non-positive permits")
    void shouldThrowExceptionForNonPositivePermits() {
      assertThrows(
          SemaphoreConfigurationException.class,
          () -> template.withKey("invalid-test").permits(0).tryAcquire());

      assertThrows(
          SemaphoreConfigurationException.class,
          () -> template.withKey("invalid-test").permits(-1).tryAcquire());
    }

    @Test
    @DisplayName("Should throw exception for non-positive permits in execute")
    void shouldThrowExceptionForNonPositivePermitsInExecute() {
      assertThrows(
          SemaphoreConfigurationException.class,
          () -> template.withKey("invalid-test").permits(0).execute(() -> "result"));

      assertThrows(
          SemaphoreConfigurationException.class,
          () -> template.withKey("invalid-test").permits(-1).execute(() -> "result"));
    }
  }

  @Nested
  @DisplayName("Semaphore Initialization")
  class SemaphoreInitialization {

    @Test
    @DisplayName("Should initialize semaphore with correct permit count")
    void shouldInitializeSemaphoreWithCorrectPermitCount() throws InterruptedException {
      int maxPermits = 3;
      AtomicInteger concurrentAcquisitions = new AtomicInteger(0);
      AtomicInteger maxConcurrent = new AtomicInteger(0);
      CountDownLatch allAcquired = new CountDownLatch(maxPermits);
      CountDownLatch testComplete = new CountDownLatch(1);

      ExecutorService executor = Executors.newFixedThreadPool(maxPermits + 1);

      // Try to acquire one more than max permits
      for (int i = 0; i < maxPermits + 1; i++) {
        executor.submit(
            () -> {
              try {
                PermitHandle handle =
                    template.withKey("init-test").permits(maxPermits).tryAcquire();
                if (handle.isAcquired()) {
                  int current = concurrentAcquisitions.incrementAndGet();
                  maxConcurrent.updateAndGet(max -> Math.max(max, current));
                  allAcquired.countDown();
                  testComplete.await(10, TimeUnit.SECONDS);
                  concurrentAcquisitions.decrementAndGet();
                  handle.close();
                }
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            });
      }

      // Wait for permits to be acquired
      Thread.sleep(500);
      testComplete.countDown();
      executor.shutdown();
      executor.awaitTermination(10, TimeUnit.SECONDS);

      assertEquals(
          maxPermits, maxConcurrent.get(), "Should acquire exactly " + maxPermits + " permits");
    }
  }
}
