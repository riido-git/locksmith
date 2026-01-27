package in.riido.locksmith.integration;

import static org.junit.jupiter.api.Assertions.*;

import in.riido.locksmith.aspect.DistributedSemaphoreAspect;
import in.riido.locksmith.autoconfigure.LocksmithProperties;
import in.riido.locksmith.autoconfigure.LocksmithProperties.SemaphoreProperties;
import in.riido.locksmith.exception.SemaphoreLeaseExpiredException;
import in.riido.locksmith.exception.SemaphoreNotAcquiredException;
import in.riido.locksmith.integration.service.SemaphoreIntegrationTestService;
import in.riido.locksmith.integration.service.SemaphoreIntegrationTestServiceImpl;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.context.support.GenericApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Integration tests for DistributedSemaphore annotation.
 *
 * @author Garvit Joshi
 * @since 2.0.0
 */
@Testcontainers
@ExtendWith(DockerAvailableCondition.class)
@DisplayName("Distributed Semaphore Integration Tests")
class DistributedSemaphoreIntegrationTest {

  private static final Logger LOG =
      LoggerFactory.getLogger(DistributedSemaphoreIntegrationTest.class);
  private static final int REDIS_PORT = 6379;

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>(DockerImageName.parse("redis:latest")).withExposedPorts(REDIS_PORT);

  private RedissonClient redissonClient;
  private SemaphoreIntegrationTestService testService;

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
            new SemaphoreProperties(
                Duration.ofMinutes(5), Duration.ofSeconds(30), "semtest:", false, false),
            null);
    DistributedSemaphoreAspect aspect =
        new DistributedSemaphoreAspect(redissonClient, properties, new GenericApplicationContext());

    AspectJProxyFactory factory =
        new AspectJProxyFactory(new SemaphoreIntegrationTestServiceImpl());
    factory.setProxyTargetClass(true);
    factory.addAspect(aspect);
    testService = factory.getProxy();
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
  @DisplayName("Basic Permit Acquisition Tests")
  class BasicPermitAcquisitionTests {

    @Test
    @DisplayName("Should acquire permit and execute method successfully")
    void shouldAcquirePermitAndExecute() {
      assertDoesNotThrow(() -> testService.simplePermitMethod());
    }

    @Test
    @DisplayName("Should resolve SpEL expression in semaphore key")
    void shouldResolveSpelExpressionInKey() {
      String result = testService.permitMethodWithSpelKey("resource-123");
      assertEquals("processed-resource-123", result);
    }

    @Test
    @DisplayName("Should allow concurrent executions up to permit limit")
    void shouldAllowConcurrentExecutionsUpToPermitLimit() throws InterruptedException {
      int threadCount = 10;
      AtomicInteger activeCount = new AtomicInteger(0);
      AtomicInteger maxConcurrent = new AtomicInteger(0);
      CountDownLatch allComplete = new CountDownLatch(threadCount);

      ExecutorService executor = Executors.newFixedThreadPool(threadCount);
      for (int i = 0; i < threadCount; i++) {
        executor.submit(
            () -> {
              testService.tryAcquirePermit(activeCount, maxConcurrent);
              allComplete.countDown();
            });
      }

      assertTrue(allComplete.await(30, TimeUnit.SECONDS));

      // With 3 permits and 10 threads, max concurrent should be <= 3
      LOG.info("Max concurrent executions: {}", maxConcurrent.get());
      assertTrue(
          maxConcurrent.get() <= 3,
          "Max concurrent should not exceed permit count of 3, but was " + maxConcurrent.get());

      executor.shutdown();
    }

    @Test
    @DisplayName("Should release permit after execution completes")
    void shouldReleasePermitAfterExecution() throws InterruptedException {
      // First, acquire permits up to limit
      CountDownLatch started = new CountDownLatch(2);
      CountDownLatch canRelease = new CountDownLatch(1);
      CountDownLatch allComplete = new CountDownLatch(3);

      ExecutorService executor = Executors.newFixedThreadPool(3);

      // Start 2 threads that hold permits
      for (int i = 0; i < 2; i++) {
        executor.submit(
            () -> {
              testService.holdPermitForDuration(started, canRelease);
              allComplete.countDown();
            });
      }

      // Wait for them to acquire permits
      assertTrue(started.await(5, TimeUnit.SECONDS));
      Thread.sleep(100); // Give time for permits to be acquired

      // Release the permits
      canRelease.countDown();

      // Now a third thread should be able to acquire
      executor.submit(
          () -> {
            testService.holdPermitForDuration(new CountDownLatch(1), new CountDownLatch(0));
            allComplete.countDown();
          });

      assertTrue(allComplete.await(10, TimeUnit.SECONDS));
      executor.shutdown();
    }
  }

  @Nested
  @DisplayName("Skip Handler Tests")
  class SkipHandlerTests {

    @Test
    @DisplayName("Should throw SemaphoreNotAcquiredException when permit not available")
    void shouldThrowExceptionWhenPermitNotAvailable() throws InterruptedException {
      CountDownLatch holdingPermit = new CountDownLatch(1);
      CountDownLatch canRelease = new CountDownLatch(1);

      ExecutorService executor = Executors.newFixedThreadPool(2);

      // First thread holds the only permit
      executor.submit(
          () -> {
            try {
              testService.throwOnPermitNotAcquired();
            } finally {
              holdingPermit.countDown();
            }
          });

      // Wait for first thread to acquire permit
      Thread.sleep(100);

      // Second thread should fail to acquire
      try {
        testService.throwOnPermitNotAcquired();
        fail("Should have thrown SemaphoreNotAcquiredException");
      } catch (SemaphoreNotAcquiredException e) {
        LOG.info("Caught expected exception: {}", e.getMessage());
        assertNotNull(e.getSemaphoreKey());
      }

      canRelease.countDown();
      executor.shutdown();
      executor.awaitTermination(10, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("Should return default value when using ReturnDefaultHandler")
    void shouldReturnDefaultWhenPermitNotAvailable() throws InterruptedException {
      CountDownLatch started = new CountDownLatch(1);
      ExecutorService executor = Executors.newSingleThreadExecutor();

      // First thread holds the only permit
      executor.submit(
          () -> {
            started.countDown();
            testService.returnDefaultOnPermitNotAcquired();
          });

      // Wait for first thread to start
      assertTrue(started.await(5, TimeUnit.SECONDS));
      Thread.sleep(50); // Ensure permit is acquired

      // Second call should return null (default) instead of throwing
      Object result = testService.returnDefaultOnPermitNotAcquired();
      assertNull(result);

      executor.shutdown();
      executor.awaitTermination(10, TimeUnit.SECONDS);
    }
  }

  @Nested
  @DisplayName("Wait Mode Tests")
  class WaitModeTests {

    @Test
    @DisplayName("Should wait and acquire permit when available")
    void shouldWaitAndAcquirePermit() throws InterruptedException {
      int threadCount = 5;
      AtomicInteger counter = new AtomicInteger(0);
      CountDownLatch allComplete = new CountDownLatch(threadCount);

      ExecutorService executor = Executors.newFixedThreadPool(threadCount);
      for (int i = 0; i < threadCount; i++) {
        executor.submit(
            () -> {
              testService.waitAndAcquirePermit(counter);
              allComplete.countDown();
            });
      }

      assertTrue(allComplete.await(30, TimeUnit.SECONDS));
      assertEquals(threadCount, counter.get(), "All threads should have executed");

      executor.shutdown();
    }

    @Test
    @DisplayName("Should execute all threads with WAIT_AND_SKIP mode")
    void shouldExecuteAllThreadsWithWaitMode() throws InterruptedException {
      int threadCount = 10;
      AtomicInteger activeCount = new AtomicInteger(0);
      AtomicInteger maxConcurrent = new AtomicInteger(0);
      AtomicInteger completedCount = new AtomicInteger(0);
      CountDownLatch allComplete = new CountDownLatch(threadCount);

      ExecutorService executor = Executors.newFixedThreadPool(threadCount);
      for (int i = 0; i < threadCount; i++) {
        executor.submit(
            () -> {
              testService.trackConcurrentExecution(activeCount, maxConcurrent, completedCount, 100);
              allComplete.countDown();
            });
      }

      assertTrue(allComplete.await(60, TimeUnit.SECONDS));

      LOG.info("Completed: {}, Max concurrent: {}", completedCount.get(), maxConcurrent.get());
      assertEquals(threadCount, completedCount.get());
      assertTrue(maxConcurrent.get() <= 5, "Max concurrent should not exceed 5 permits");

      executor.shutdown();
    }
  }

  @Nested
  @DisplayName("Lease Expiration Tests")
  class LeaseExpirationTests {

    @Test
    @DisplayName("Should log warning when execution exceeds lease time")
    void shouldLogWarningWhenLeaseExpires() {
      // This should complete but log a warning about lease expiration
      assertDoesNotThrow(() -> testService.permitWithLeaseExpiration());
    }

    @Test
    @DisplayName("Should throw exception when configured for THROW_EXCEPTION on lease expiry")
    void shouldThrowExceptionOnLeaseExpiry() {
      assertThrows(
          SemaphoreLeaseExpiredException.class, () -> testService.permitWithThrowOnLeaseExpired());
    }
  }

  @Nested
  @DisplayName("Multi-Key Tests")
  class MultiKeyTests {

    @Test
    @DisplayName("Should allow parallel execution with different keys")
    void shouldAllowParallelExecutionWithDifferentKeys() throws InterruptedException {
      int keyCount = 5;
      int threadsPerKey = 3;
      AtomicInteger totalCounter = new AtomicInteger(0);
      CountDownLatch allComplete = new CountDownLatch(keyCount * threadsPerKey);

      ExecutorService executor = Executors.newFixedThreadPool(keyCount * threadsPerKey);

      for (int k = 0; k < keyCount; k++) {
        final String key = "key-" + k;
        for (int t = 0; t < threadsPerKey; t++) {
          executor.submit(
              () -> {
                testService.multiKeyPermit(key, totalCounter);
                allComplete.countDown();
              });
        }
      }

      assertTrue(allComplete.await(30, TimeUnit.SECONDS));
      assertEquals(keyCount * threadsPerKey, totalCounter.get());

      executor.shutdown();
    }
  }

  @Nested
  @DisplayName("Exception Handling Tests")
  class ExceptionHandlingTests {

    @Test
    @DisplayName("Should release permit even when method throws exception")
    void shouldReleasePermitOnException() throws InterruptedException {
      // First, cause an exception
      assertThrows(RuntimeException.class, () -> testService.permitWithException());

      // Sleep briefly to ensure cleanup
      Thread.sleep(100);

      // Now should be able to acquire permit again
      boolean acquired = testService.acquirePermitAfterException();
      assertTrue(acquired, "Should be able to acquire permit after exception");
    }

    @Test
    @DisplayName("Should properly release permits after multiple exceptions")
    void shouldReleasePermitsAfterMultipleExceptions() throws InterruptedException {
      int iterations = 10;

      for (int i = 0; i < iterations; i++) {
        assertThrows(RuntimeException.class, () -> testService.permitWithException());
        Thread.sleep(50);

        // Verify we can still acquire
        boolean acquired = testService.acquirePermitAfterException();
        assertTrue(acquired, "Should acquire permit after exception #" + (i + 1));
      }
    }
  }

  @Nested
  @DisplayName("High Contention Tests")
  class HighContentionTests {

    @Test
    @DisplayName("Should handle high contention gracefully")
    void shouldHandleHighContention() throws InterruptedException {
      int threadCount = 50;
      AtomicInteger successCount = new AtomicInteger(0);
      AtomicInteger skipCount = new AtomicInteger(0);
      CountDownLatch allComplete = new CountDownLatch(threadCount);

      ExecutorService executor = Executors.newFixedThreadPool(threadCount);
      for (int i = 0; i < threadCount; i++) {
        executor.submit(
            () -> {
              Boolean result = testService.highContentionPermit(successCount, skipCount);
              if (result == null || !result) {
                skipCount.incrementAndGet();
              }
              allComplete.countDown();
            });
      }

      assertTrue(allComplete.await(30, TimeUnit.SECONDS));

      LOG.info("Success: {}, Skipped: {}", successCount.get(), skipCount.get());
      assertTrue(successCount.get() > 0, "Some executions should succeed");
      assertEquals(
          threadCount, successCount.get() + skipCount.get(), "All threads should be accounted for");

      executor.shutdown();
    }
  }

  @Nested
  @DisplayName("Order and Fairness Tests")
  class OrderAndFairnessTests {

    @Test
    @DisplayName("Should maintain reasonable ordering with WAIT_AND_SKIP")
    void shouldMaintainReasonableOrdering() throws InterruptedException {
      int threadCount = 10;
      List<Integer> executionOrder = Collections.synchronizedList(new ArrayList<>());
      CountDownLatch allComplete = new CountDownLatch(threadCount);

      ExecutorService executor = Executors.newFixedThreadPool(threadCount);
      for (int i = 0; i < threadCount; i++) {
        final int id = i;
        executor.submit(
            () -> {
              testService.orderedPermitAcquisition(id, executionOrder);
              allComplete.countDown();
            });
        Thread.sleep(10); // Small delay to create ordering
      }

      assertTrue(allComplete.await(60, TimeUnit.SECONDS));

      LOG.info("Execution order: {}", executionOrder);
      assertEquals(threadCount, executionOrder.size());

      executor.shutdown();
    }
  }
}
