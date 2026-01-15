package in.riido.locksmith.integration;

import static org.junit.jupiter.api.Assertions.*;

import in.riido.locksmith.aspect.DistributedSemaphoreAspect;
import in.riido.locksmith.autoconfigure.LocksmithProperties;
import in.riido.locksmith.autoconfigure.LocksmithProperties.SemaphoreProperties;
import in.riido.locksmith.integration.service.SemaphoreConcurrencyTestService;
import in.riido.locksmith.integration.service.SemaphoreConcurrencyTestServiceImpl;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * Concurrent access tests for distributed semaphores.
 *
 * @author Garvit Joshi
 * @since 2.0.0
 */
@Testcontainers
@ExtendWith(DockerAvailableCondition.class)
@DisplayName("Semaphore Concurrent Access Tests")
class SemaphoreConcurrentAccessTest {

  private static final Logger LOG = LoggerFactory.getLogger(SemaphoreConcurrentAccessTest.class);
  private static final int REDIS_PORT = 6379;

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>(DockerImageName.parse("redis:latest")).withExposedPorts(REDIS_PORT);

  private RedissonClient redissonClient;
  private SemaphoreConcurrencyTestService testService;

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
                Duration.ofMinutes(5), Duration.ofSeconds(30), "concurrent:", false));
    DistributedSemaphoreAspect aspect =
        new DistributedSemaphoreAspect(redissonClient, properties, new GenericApplicationContext());

    AspectJProxyFactory factory =
        new AspectJProxyFactory(new SemaphoreConcurrencyTestServiceImpl());
    factory.setProxyTargetClass(true);
    factory.addAspect(aspect);
    testService = factory.getProxy();
  }

  @AfterEach
  void tearDown() {
    if (redissonClient != null && !redissonClient.isShutdown()) {
      redissonClient.shutdown();
    }
  }

  @Nested
  @DisplayName("Permit Limit Enforcement Tests")
  class PermitLimitEnforcementTests {

    @Test
    @DisplayName("Should enforce 2 permit limit")
    void shouldEnforceTwoPermitLimit() throws InterruptedException {
      int threadCount = 20;
      AtomicInteger activeCount = new AtomicInteger(0);
      AtomicInteger maxConcurrent = new AtomicInteger(0);
      CountDownLatch allComplete = new CountDownLatch(threadCount);

      ExecutorService executor = Executors.newFixedThreadPool(threadCount);
      for (int i = 0; i < threadCount; i++) {
        executor.submit(
            () -> {
              testService.twoPermitMethod(activeCount, maxConcurrent);
              allComplete.countDown();
            });
      }

      assertTrue(allComplete.await(60, TimeUnit.SECONDS));

      LOG.info("Max concurrent with 2 permits: {}", maxConcurrent.get());
      assertTrue(
          maxConcurrent.get() <= 2,
          "Max concurrent should not exceed 2, but was " + maxConcurrent.get());

      executor.shutdown();
    }

    @Test
    @DisplayName("Should enforce 10 permit limit under high load")
    void shouldEnforceTenPermitLimit() throws InterruptedException {
      int threadCount = 50;
      AtomicInteger activeCount = new AtomicInteger(0);
      AtomicInteger maxConcurrent = new AtomicInteger(0);
      CountDownLatch allComplete = new CountDownLatch(threadCount);

      ExecutorService executor = Executors.newFixedThreadPool(threadCount);
      for (int i = 0; i < threadCount; i++) {
        executor.submit(
            () -> {
              testService.tenPermitMethod(activeCount, maxConcurrent);
              allComplete.countDown();
            });
      }

      assertTrue(allComplete.await(60, TimeUnit.SECONDS));

      LOG.info("Max concurrent with 10 permits: {}", maxConcurrent.get());
      assertTrue(
          maxConcurrent.get() <= 10,
          "Max concurrent should not exceed 10, but was " + maxConcurrent.get());
      assertTrue(
          maxConcurrent.get() >= 5,
          "Should utilize most permits, but only used " + maxConcurrent.get());

      executor.shutdown();
    }

    @Test
    @DisplayName("Should track concurrent executions accurately")
    void shouldTrackConcurrentExecutionsAccurately() throws InterruptedException {
      int threadCount = 30;
      AtomicInteger activeCount = new AtomicInteger(0);
      AtomicInteger maxConcurrent = new AtomicInteger(0);
      AtomicInteger completedCount = new AtomicInteger(0);
      CountDownLatch allComplete = new CountDownLatch(threadCount);

      ExecutorService executor = Executors.newFixedThreadPool(threadCount);
      for (int i = 0; i < threadCount; i++) {
        executor.submit(
            () -> {
              testService.trackExecution(activeCount, maxConcurrent, completedCount, 100);
              allComplete.countDown();
            });
      }

      assertTrue(allComplete.await(120, TimeUnit.SECONDS));

      LOG.info("Completed: {}, Max concurrent: {}", completedCount.get(), maxConcurrent.get());
      assertEquals(threadCount, completedCount.get(), "All threads should complete");
      assertTrue(maxConcurrent.get() <= 5, "Max concurrent should not exceed 5 permits");

      executor.shutdown();
    }
  }

  @Nested
  @DisplayName("Skip vs Wait Mode Tests")
  class SkipVsWaitModeTests {

    @Test
    @DisplayName("Should skip executions when permits exhausted with SKIP_IMMEDIATELY")
    void shouldSkipWhenPermitsExhausted() throws InterruptedException {
      int threadCount = 20;
      AtomicInteger successCount = new AtomicInteger(0);
      CountDownLatch allComplete = new CountDownLatch(threadCount);

      ExecutorService executor = Executors.newFixedThreadPool(threadCount);
      for (int i = 0; i < threadCount; i++) {
        executor.submit(
            () -> {
              testService.skipOnFailure(successCount);
              allComplete.countDown();
            });
      }

      assertTrue(allComplete.await(30, TimeUnit.SECONDS));

      LOG.info("Successful executions with 3 permits: {}", successCount.get());
      // With skip mode, not all should succeed
      assertTrue(successCount.get() < threadCount, "Some executions should be skipped");
      assertTrue(successCount.get() > 0, "Some executions should succeed");

      executor.shutdown();
    }

    @Test
    @DisplayName("Should complete all executions with WAIT_AND_SKIP mode")
    void shouldCompleteAllWithWaitMode() throws InterruptedException {
      int threadCount = 15;
      AtomicInteger counter = new AtomicInteger(0);
      CountDownLatch allComplete = new CountDownLatch(threadCount);

      ExecutorService executor = Executors.newFixedThreadPool(threadCount);
      for (int i = 0; i < threadCount; i++) {
        executor.submit(
            () -> {
              testService.waitForPermit(counter);
              allComplete.countDown();
            });
      }

      assertTrue(allComplete.await(60, TimeUnit.SECONDS));

      assertEquals(threadCount, counter.get(), "All threads should have executed");

      executor.shutdown();
    }
  }

  @Nested
  @DisplayName("Key Isolation Tests")
  class KeyIsolationTests {

    @Test
    @DisplayName("Should allow parallel execution with different keys")
    void shouldAllowParallelWithDifferentKeys() throws InterruptedException {
      int keyCount = 5;
      int threadsPerKey = 10;
      AtomicInteger totalCounter = new AtomicInteger(0);
      CountDownLatch allComplete = new CountDownLatch(keyCount * threadsPerKey);

      ExecutorService executor = Executors.newFixedThreadPool(keyCount * threadsPerKey);

      long startTime = System.currentTimeMillis();

      for (int k = 0; k < keyCount; k++) {
        final String key = "isolated-key-" + k;
        for (int t = 0; t < threadsPerKey; t++) {
          executor.submit(
              () -> {
                testService.isolatedKeyMethod(key, totalCounter);
                allComplete.countDown();
              });
        }
      }

      assertTrue(allComplete.await(60, TimeUnit.SECONDS));
      long duration = System.currentTimeMillis() - startTime;

      LOG.info(
          "Completed {} executions across {} keys in {}ms", totalCounter.get(), keyCount, duration);
      assertEquals(keyCount * threadsPerKey, totalCounter.get());

      // Should be faster than sequential execution due to key isolation
      // Each key has 2 permits, so with 5 keys = 10 concurrent possible
      // 50 threads * 50ms sleep / 10 concurrent = ~250ms minimum
      assertTrue(duration < 10000, "Should complete relatively quickly due to parallelism");

      executor.shutdown();
    }
  }

  @Nested
  @DisplayName("Race Condition Protection Tests")
  class RaceConditionProtectionTests {

    @Test
    @DisplayName("Should protect counter from race conditions with single permit")
    void shouldProtectCounterWithSinglePermit() throws InterruptedException {
      int iterations = 100;
      AtomicInteger counter = new AtomicInteger(0);
      CountDownLatch allComplete = new CountDownLatch(iterations);

      ExecutorService executor = Executors.newFixedThreadPool(20);
      for (int i = 0; i < iterations; i++) {
        executor.submit(
            () -> {
              testService.protectedIncrement(counter);
              allComplete.countDown();
            });
      }

      assertTrue(allComplete.await(120, TimeUnit.SECONDS));

      LOG.info("Final counter value: {} (expected: {})", counter.get(), iterations);
      assertEquals(
          iterations, counter.get(), "Counter should equal iterations with proper synchronization");

      executor.shutdown();
    }
  }

  @Nested
  @DisplayName("Mutual Exclusion Tests")
  class MutualExclusionTests {

    @Test
    @DisplayName("Should prevent concurrent access when only 1 permit available")
    void shouldPreventConcurrentAccessWithSinglePermit() throws InterruptedException {
      AtomicBoolean isExecuting = new AtomicBoolean(false);
      CountDownLatch started = new CountDownLatch(1);
      CountDownLatch complete = new CountDownLatch(1);

      ExecutorService executor = Executors.newFixedThreadPool(2);

      // Start long-running operation
      executor.submit(
          () -> {
            started.countDown();
            testService.longRunningOperation(isExecuting, 1000);
            complete.countDown();
          });

      assertTrue(started.await(5, TimeUnit.SECONDS));
      Thread.sleep(100); // Ensure operation is running

      // Try to acquire while other is executing
      assertTrue(isExecuting.get(), "First operation should be executing");
      boolean acquired = testService.canAcquireWhileOtherExecuting();
      assertFalse(acquired, "Should not acquire permit while another is executing");

      complete.await(5, TimeUnit.SECONDS);
      executor.shutdown();
    }
  }

  @Nested
  @DisplayName("Throughput Tests")
  class ThroughputTests {

    @Test
    @DisplayName("Should achieve reasonable throughput with wait mode")
    void shouldAchieveReasonableThroughput() throws InterruptedException {
      int threadCount = 20;
      int iterationsPerThread = 10;
      AtomicInteger completedCount = new AtomicInteger(0);
      CountDownLatch allComplete = new CountDownLatch(threadCount);

      ExecutorService executor = Executors.newFixedThreadPool(threadCount);

      long startTime = System.currentTimeMillis();

      for (int t = 0; t < threadCount; t++) {
        executor.submit(
            () -> {
              AtomicInteger dummy = new AtomicInteger();
              for (int i = 0; i < iterationsPerThread; i++) {
                testService.waitForPermit(dummy);
                completedCount.incrementAndGet();
              }
              allComplete.countDown();
            });
      }

      assertTrue(allComplete.await(180, TimeUnit.SECONDS));
      long duration = System.currentTimeMillis() - startTime;

      int totalOperations = threadCount * iterationsPerThread;
      double throughput = (totalOperations * 1000.0) / duration;

      LOG.info(
          "Completed {} operations in {}ms, throughput: {}/sec",
          completedCount.get(),
          duration,
          throughput);

      assertEquals(totalOperations, completedCount.get());
      assertTrue(throughput > 10, "Should achieve at least 10 ops/sec");

      executor.shutdown();
    }
  }

  @Nested
  @DisplayName("Order Tracking Tests")
  class OrderTrackingTests {

    @Test
    @DisplayName("Should track execution order with limited permits")
    void shouldTrackExecutionOrder() throws InterruptedException {
      int threadCount = 10;
      List<Integer> executionOrder = Collections.synchronizedList(new ArrayList<>());
      CountDownLatch allComplete = new CountDownLatch(threadCount);

      ExecutorService executor = Executors.newFixedThreadPool(threadCount);

      for (int i = 0; i < threadCount; i++) {
        final int id = i;
        executor.submit(
            () -> {
              testService.trackOrder(id, executionOrder);
              allComplete.countDown();
            });
        Thread.sleep(20); // Stagger starts
      }

      assertTrue(allComplete.await(60, TimeUnit.SECONDS));

      LOG.info("Execution order: {}", executionOrder);
      assertEquals(threadCount, executionOrder.size(), "All threads should have executed");

      executor.shutdown();
    }
  }
}
