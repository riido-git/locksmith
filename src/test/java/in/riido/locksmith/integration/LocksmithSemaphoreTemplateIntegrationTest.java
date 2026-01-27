package in.riido.locksmith.integration;

import static org.junit.jupiter.api.Assertions.*;

import in.riido.locksmith.autoconfigure.LocksmithProperties;
import in.riido.locksmith.template.LocksmithSemaphoreTemplate;
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
                Duration.ofMinutes(1), Duration.ofSeconds(10), "test:", false, false),
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
      String permitId = template.tryAcquirePermit("basic-test", 5);
      assertNotNull(permitId);
      template.releasePermit("basic-test", permitId);
    }

    @Test
    @DisplayName("Should execute callback while holding permit")
    void shouldExecuteCallbackWithPermit() throws Exception {
      AtomicInteger executed = new AtomicInteger(0);

      String result =
          template.executeWithPermit(
              "callback-test",
              5,
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
              template.executeWithPermit(
                  "exception-test",
                  5,
                  () -> {
                    throw new RuntimeException("Test error");
                  }));

      // Should be able to acquire permit again
      String permitId = template.tryAcquirePermit("exception-test", 5);
      assertNotNull(permitId);
      template.releasePermit("exception-test", permitId);
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
                    .forKey("limit-test", maxPermits)
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
    @DisplayName("Should return null when all permits are held")
    void shouldReturnNullWhenAllPermitsHeld() throws Exception {
      int maxPermits = 2;
      CountDownLatch permitsAcquired = new CountDownLatch(maxPermits);
      CountDownLatch testComplete = new CountDownLatch(1);

      ExecutorService executor = Executors.newFixedThreadPool(maxPermits);

      // Acquire all permits
      for (int i = 0; i < maxPermits; i++) {
        executor.submit(
            () -> {
              try {
                String permitId = template.tryAcquirePermit("full-test", maxPermits);
                if (permitId != null) {
                  permitsAcquired.countDown();
                  testComplete.await(10, TimeUnit.SECONDS);
                  template.releasePermit("full-test", permitId);
                }
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            });
      }

      assertTrue(permitsAcquired.await(5, TimeUnit.SECONDS));

      // Try to acquire when all permits are held
      String permitId = template.tryAcquirePermit("full-test", maxPermits);
      assertNull(permitId);

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
                String permitId = template.tryAcquirePermit("wait-test", maxPermits);
                if (permitId != null) {
                  firstPermitAcquired.countDown();
                  try {
                    Thread.sleep(200);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                  template.releasePermit("wait-test", permitId);
                }
              });

      Thread secondThread =
          new Thread(
              () -> {
                try {
                  firstPermitAcquired.await();
                  String permitId =
                      template
                          .forKey("wait-test", maxPermits)
                          .waitTime(Duration.ofSeconds(5))
                          .tryAcquire();
                  if (permitId != null) {
                    secondPermitAcquired.countDown();
                    template.releasePermit("wait-test", permitId);
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
                template.executeWithPermit(
                    key,
                    1, // Only 1 permit each, but different keys
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
          IllegalArgumentException.class, () -> template.tryAcquirePermit("invalid-test", 0));

      assertThrows(
          IllegalArgumentException.class, () -> template.tryAcquirePermit("invalid-test", -1));
    }

    @Test
    @DisplayName("Should throw exception for non-positive permits in executeWithPermit")
    void shouldThrowExceptionForNonPositivePermitsInExecute() {
      assertThrows(
          IllegalArgumentException.class,
          () -> template.executeWithPermit("invalid-test", 0, () -> "result"));

      assertThrows(
          IllegalArgumentException.class,
          () -> template.executeWithPermit("invalid-test", -1, () -> "result"));
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
                String permitId = template.tryAcquirePermit("init-test", maxPermits);
                if (permitId != null) {
                  int current = concurrentAcquisitions.incrementAndGet();
                  maxConcurrent.updateAndGet(max -> Math.max(max, current));
                  allAcquired.countDown();
                  testComplete.await(10, TimeUnit.SECONDS);
                  concurrentAcquisitions.decrementAndGet();
                  template.releasePermit("init-test", permitId);
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
