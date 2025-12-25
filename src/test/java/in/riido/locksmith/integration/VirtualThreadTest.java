package in.riido.locksmith.integration;

import static org.junit.jupiter.api.Assertions.*;

import in.riido.locksmith.aspect.DistributedLockAspect;
import in.riido.locksmith.autoconfigure.LocksmithProperties;
import in.riido.locksmith.integration.service.VirtualThreadTestService;
import in.riido.locksmith.integration.service.VirtualThreadTestServiceImpl;
import java.lang.reflect.Method;
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
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import org.junit.jupiter.api.extension.ExtendWith;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@ExtendWith(DockerAvailableCondition.class)
@EnabledForJreRange(min = JRE.JAVA_21)
@DisplayName("Virtual Thread Integration Tests")
class VirtualThreadTest {

  private static final Logger LOG = LoggerFactory.getLogger(VirtualThreadTest.class);
  private static final int REDIS_PORT = 6379;

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>(DockerImageName.parse("redis:latest")).withExposedPorts(REDIS_PORT);

  private RedissonClient redissonClient;
  private VirtualThreadTestService testService;

  private static ExecutorService newVirtualThreadExecutor() {
    try {
      Method method = Executors.class.getMethod("newVirtualThreadPerTaskExecutor");
      return (ExecutorService) method.invoke(null);
    } catch (Exception e) {
      throw new RuntimeException("Virtual threads not available", e);
    }
  }

  private static void shutdownExecutor(ExecutorService executor) {
    executor.shutdown();
    try {
      if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
        executor.shutdownNow();
      }
    } catch (InterruptedException e) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  @BeforeEach
  void setUp() {
    Config config = new Config();
    config
        .useSingleServer()
        .setAddress("redis://" + redis.getHost() + ":" + redis.getMappedPort(REDIS_PORT));
    redissonClient = Redisson.create(config);

    LocksmithProperties properties =
        new LocksmithProperties(Duration.ofMinutes(1), Duration.ofSeconds(30), "vthread:", false);
    DistributedLockAspect aspect = new DistributedLockAspect(redissonClient, properties);

    AspectJProxyFactory factory = new AspectJProxyFactory(new VirtualThreadTestServiceImpl());
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
  @DisplayName("Virtual Thread Lock Exclusivity Tests")
  class VirtualThreadLockExclusivityTests {

    @Test
    @DisplayName("Should maintain lock exclusivity with virtual threads")
    void shouldMaintainLockExclusivityWithVirtualThreads() throws InterruptedException {
      int taskCount = 100;
      AtomicBoolean concurrentExecution = new AtomicBoolean(false);
      AtomicInteger activeThreads = new AtomicInteger(0);
      CountDownLatch allComplete = new CountDownLatch(taskCount);

      ExecutorService executor = newVirtualThreadExecutor();
      try {
        for (int i = 0; i < taskCount; i++) {
          executor.submit(
              () -> {
                try {
                  testService.exclusiveLockMethod(activeThreads, concurrentExecution);
                } finally {
                  allComplete.countDown();
                }
              });
        }

        assertTrue(allComplete.await(60, TimeUnit.SECONDS));
        assertFalse(
            concurrentExecution.get(),
            "Concurrent execution detected with virtual threads - lock exclusivity violated!");
      } finally {
        shutdownExecutor(executor);
      }
    }

    @Test
    @DisplayName("Should handle high concurrency with many virtual threads")
    void shouldHandleHighConcurrencyWithManyVirtualThreads() throws InterruptedException {
      int taskCount = 1000;
      AtomicInteger successCount = new AtomicInteger(0);
      AtomicInteger skipCount = new AtomicInteger(0);
      CountDownLatch allComplete = new CountDownLatch(taskCount);

      long startTime = System.currentTimeMillis();

      ExecutorService executor = newVirtualThreadExecutor();
      try {
        for (int i = 0; i < taskCount; i++) {
          executor.submit(
              () -> {
                try {
                  boolean result = testService.tryAcquireLock();
                  if (result) {
                    successCount.incrementAndGet();
                  } else {
                    skipCount.incrementAndGet();
                  }
                } finally {
                  allComplete.countDown();
                }
              });
        }

        assertTrue(allComplete.await(60, TimeUnit.SECONDS));
      } finally {
        shutdownExecutor(executor);
      }

      long duration = System.currentTimeMillis() - startTime;
      LOG.info(
          "Virtual thread test: {} tasks in {}ms, {} successful, {} skipped",
          taskCount,
          duration,
          successCount.get(),
          skipCount.get());

      assertTrue(successCount.get() > 0, "At least some tasks should acquire the lock");
      assertEquals(
          taskCount, successCount.get() + skipCount.get(), "All tasks should be accounted for");
    }

    @Test
    @DisplayName("Should prevent concurrent execution under virtual thread contention")
    void shouldPreventConcurrentExecutionUnderVirtualThreadContention()
        throws InterruptedException {
      int taskCount = 50;
      AtomicBoolean concurrentExecution = new AtomicBoolean(false);
      AtomicInteger activeThreads = new AtomicInteger(0);
      AtomicInteger successfulExecutions = new AtomicInteger(0);
      CountDownLatch allComplete = new CountDownLatch(taskCount);

      ExecutorService executor = newVirtualThreadExecutor();
      try {
        for (int i = 0; i < taskCount; i++) {
          executor.submit(
              () -> {
                try {
                  boolean executed =
                      testService.contentionTestMethod(activeThreads, concurrentExecution);
                  if (executed) {
                    successfulExecutions.incrementAndGet();
                  }
                } finally {
                  allComplete.countDown();
                }
              });
        }

        assertTrue(allComplete.await(60, TimeUnit.SECONDS));
        assertFalse(
            concurrentExecution.get(),
            "Concurrent execution detected under virtual thread contention!");
        assertTrue(successfulExecutions.get() > 0, "At least some executions should succeed");
      } finally {
        shutdownExecutor(executor);
      }
    }
  }

  @Nested
  @DisplayName("Virtual Thread Read-Write Lock Tests")
  class VirtualThreadReadWriteLockTests {

    @Test
    @DisplayName("Should allow multiple concurrent readers with virtual threads")
    void shouldAllowMultipleConcurrentReadersWithVirtualThreads() throws InterruptedException {
      int readerCount = 50;
      AtomicInteger maxConcurrentReaders = new AtomicInteger(0);
      AtomicInteger currentReaders = new AtomicInteger(0);
      CountDownLatch allComplete = new CountDownLatch(readerCount);
      CountDownLatch allStarted = new CountDownLatch(readerCount);

      ExecutorService executor = newVirtualThreadExecutor();
      try {
        for (int i = 0; i < readerCount; i++) {
          executor.submit(
              () -> {
                try {
                  allStarted.countDown();
                  allStarted.await(5, TimeUnit.SECONDS);
                  testService.readOperation(currentReaders, maxConcurrentReaders);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                } finally {
                  allComplete.countDown();
                }
              });
        }

        assertTrue(allComplete.await(30, TimeUnit.SECONDS));
        assertTrue(
            maxConcurrentReaders.get() > 1,
            "Should have had multiple concurrent readers with virtual threads, but got: "
                + maxConcurrentReaders.get());
      } finally {
        shutdownExecutor(executor);
      }
    }

    @Test
    @DisplayName("Should block writers while readers hold lock with virtual threads")
    void shouldBlockWritersWhileReadersHoldLockWithVirtualThreads() throws InterruptedException {
      AtomicBoolean readerActive = new AtomicBoolean(false);
      AtomicBoolean writerExecutedDuringRead = new AtomicBoolean(false);
      CountDownLatch readerStarted = new CountDownLatch(1);
      CountDownLatch allComplete = new CountDownLatch(2);

      ExecutorService executor = newVirtualThreadExecutor();
      try {
        executor.submit(
            () -> {
              try {
                testService.longReadOperation(readerActive, readerStarted);
              } finally {
                allComplete.countDown();
              }
            });

        assertTrue(readerStarted.await(5, TimeUnit.SECONDS));

        executor.submit(
            () -> {
              try {
                Thread.sleep(100);
                if (readerActive.get()) {
                  boolean executed = testService.tryWriteOperation();
                  if (executed) {
                    writerExecutedDuringRead.set(true);
                  }
                }
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } finally {
                allComplete.countDown();
              }
            });

        assertTrue(allComplete.await(15, TimeUnit.SECONDS));
        assertFalse(
            writerExecutedDuringRead.get(),
            "Writer should not execute while reader holds lock with virtual threads");
      } finally {
        shutdownExecutor(executor);
      }
    }
  }

  @Nested
  @DisplayName("Virtual Thread Lock Key Isolation Tests")
  class VirtualThreadLockKeyIsolationTests {

    @Test
    @DisplayName("Should allow parallel execution with different lock keys using virtual threads")
    void shouldAllowParallelExecutionWithDifferentLockKeysUsingVirtualThreads()
        throws InterruptedException {
      int keyCount = 100;
      AtomicInteger concurrentExecutions = new AtomicInteger(0);
      AtomicInteger maxConcurrentExecutions = new AtomicInteger(0);
      CountDownLatch allStarted = new CountDownLatch(keyCount);
      CountDownLatch allComplete = new CountDownLatch(keyCount);

      ExecutorService executor = newVirtualThreadExecutor();
      try {
        for (int i = 0; i < keyCount; i++) {
          final String lockKey = "key-" + i;
          executor.submit(
              () -> {
                try {
                  allStarted.countDown();
                  allStarted.await(10, TimeUnit.SECONDS);
                  testService.isolatedLockMethod(
                      lockKey, concurrentExecutions, maxConcurrentExecutions);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                } finally {
                  allComplete.countDown();
                }
              });
        }

        assertTrue(allComplete.await(60, TimeUnit.SECONDS));
        assertTrue(
            maxConcurrentExecutions.get() > 1,
            "Should have had concurrent executions with different keys using virtual threads, but got: "
                + maxConcurrentExecutions.get());
        LOG.info(
            "Virtual thread key isolation: {} max concurrent executions with {} unique keys",
            maxConcurrentExecutions.get(),
            keyCount);
      } finally {
        shutdownExecutor(executor);
      }
    }
  }

  @Nested
  @DisplayName("Virtual Thread Wait Mode Tests")
  class VirtualThreadWaitModeTests {

    @Test
    @DisplayName("Should wait and execute sequentially with virtual threads")
    void shouldWaitAndExecuteSequentiallyWithVirtualThreads() throws InterruptedException {
      int taskCount = 10;
      List<Integer> executionOrder = Collections.synchronizedList(new ArrayList<>());
      CountDownLatch allComplete = new CountDownLatch(taskCount);

      ExecutorService executor = newVirtualThreadExecutor();
      try {
        for (int i = 0; i < taskCount; i++) {
          final int index = i;
          executor.submit(
              () -> {
                try {
                  Thread.sleep(index * 10L);
                  testService.waitAndExecuteMethod(index, executionOrder);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                } finally {
                  allComplete.countDown();
                }
              });
        }

        assertTrue(allComplete.await(60, TimeUnit.SECONDS));
        assertEquals(taskCount, executionOrder.size(), "All tasks should execute");
      } finally {
        shutdownExecutor(executor);
      }
    }

    @Test
    @DisplayName("Should handle burst of virtual threads waiting for lock")
    void shouldHandleBurstOfVirtualThreadsWaitingForLock() throws InterruptedException {
      int burstSize = 50;
      AtomicInteger completedCount = new AtomicInteger(0);
      AtomicBoolean concurrentExecution = new AtomicBoolean(false);
      AtomicInteger activeThreads = new AtomicInteger(0);
      CountDownLatch allReady = new CountDownLatch(burstSize);
      CountDownLatch startSignal = new CountDownLatch(1);
      CountDownLatch allComplete = new CountDownLatch(burstSize);

      ExecutorService executor = newVirtualThreadExecutor();
      try {
        for (int i = 0; i < burstSize; i++) {
          executor.submit(
              () -> {
                try {
                  allReady.countDown();
                  startSignal.await();
                  testService.waitForLockMethod(activeThreads, concurrentExecution);
                  completedCount.incrementAndGet();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                } finally {
                  allComplete.countDown();
                }
              });
        }

        assertTrue(allReady.await(10, TimeUnit.SECONDS));
        startSignal.countDown();

        assertTrue(allComplete.await(120, TimeUnit.SECONDS));
        assertFalse(
            concurrentExecution.get(), "Concurrent execution detected in virtual thread burst!");
        assertEquals(burstSize, completedCount.get(), "All virtual threads should complete");
      } finally {
        shutdownExecutor(executor);
      }
    }
  }

  @Nested
  @DisplayName("Virtual Thread Performance Tests")
  class VirtualThreadPerformanceTests {

    @Test
    @DisplayName("Should demonstrate virtual thread efficiency with many waiting tasks")
    void shouldDemonstrateVirtualThreadEfficiencyWithManyWaitingTasks()
        throws InterruptedException {
      int taskCount = 500;
      AtomicInteger completedCount = new AtomicInteger(0);
      CountDownLatch allComplete = new CountDownLatch(taskCount);

      long startTime = System.currentTimeMillis();

      ExecutorService executor = newVirtualThreadExecutor();
      try {
        for (int i = 0; i < taskCount; i++) {
          final String key = "perf-key-" + (i % 10);
          executor.submit(
              () -> {
                try {
                  testService.performanceTestMethod(key);
                  completedCount.incrementAndGet();
                } finally {
                  allComplete.countDown();
                }
              });
        }

        assertTrue(allComplete.await(120, TimeUnit.SECONDS));
      } finally {
        shutdownExecutor(executor);
      }

      long duration = System.currentTimeMillis() - startTime;
      double throughput = (completedCount.get() * 1000.0) / duration;

      LOG.info(
          "Virtual thread performance: {} tasks completed in {}ms, throughput: {}/sec",
          completedCount.get(),
          duration,
          String.format("%.2f", throughput));

      assertEquals(taskCount, completedCount.get(), "All tasks should complete");
    }
  }
}
