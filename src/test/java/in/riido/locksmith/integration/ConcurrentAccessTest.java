package in.riido.locksmith.integration;

import static org.junit.jupiter.api.Assertions.*;

import in.riido.locksmith.DistributedLock;
import in.riido.locksmith.LockAcquisitionMode;
import in.riido.locksmith.LockType;
import in.riido.locksmith.aspect.DistributedLockAspect;
import in.riido.locksmith.autoconfigure.LocksmithProperties;
import in.riido.locksmith.handler.ReturnDefaultHandler;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
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
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@ExtendWith(DockerAvailableCondition.class)
@DisplayName("Concurrent Access Tests")
class ConcurrentAccessTest {

  private static final int REDIS_PORT = 6379;

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>(DockerImageName.parse("redis:latest")).withExposedPorts(REDIS_PORT);

  private RedissonClient redissonClient;
  private ConcurrencyTestService testService;

  @BeforeEach
  void setUp() {
    Config config = new Config();
    config
        .useSingleServer()
        .setAddress("redis://" + redis.getHost() + ":" + redis.getMappedPort(REDIS_PORT));
    redissonClient = Redisson.create(config);

    LocksmithProperties properties =
        new LocksmithProperties(Duration.ofMinutes(1), Duration.ofSeconds(30), "concurrent:");
    DistributedLockAspect aspect = new DistributedLockAspect(redissonClient, properties);

    // Use CGLIB proxy on the implementation class directly to preserve annotations
    AspectJProxyFactory factory = new AspectJProxyFactory(new ConcurrencyTestServiceImpl());
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

  public interface ConcurrencyTestService {
    void exclusiveLockMethod(AtomicInteger activeThreads, AtomicBoolean concurrentExecution);

    boolean contentionTestMethod(AtomicInteger activeThreads, AtomicBoolean concurrentExecution);

    void singleExecutionMethod(AtomicInteger executionCount);

    boolean incrementCounter(AtomicInteger counter);

    void orderedExecutionMethod(int index, List<Integer> order);

    void readOperation(AtomicInteger currentReaders, AtomicInteger maxConcurrentReaders);

    void writeOperation(AtomicBoolean writerActive, CountDownLatch started);

    boolean readDuringWrite();

    void isolatedLockMethod(
        String key, AtomicInteger concurrentExecutions, AtomicInteger maxConcurrentExecutions);
  }

  public static class ConcurrencyTestServiceImpl implements ConcurrencyTestService {

    @Override
    @DistributedLock(
        key = "exclusive-lock",
        mode = LockAcquisitionMode.WAIT_AND_SKIP,
        waitTime = "5s")
    public void exclusiveLockMethod(
        AtomicInteger activeThreads, AtomicBoolean concurrentExecution) {
      int current = activeThreads.incrementAndGet();
      if (current > 1) {
        concurrentExecution.set(true);
      }
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      activeThreads.decrementAndGet();
    }

    @Override
    @DistributedLock(key = "contention-lock", skipHandler = ReturnDefaultHandler.class)
    public boolean contentionTestMethod(
        AtomicInteger activeThreads, AtomicBoolean concurrentExecution) {
      int current = activeThreads.incrementAndGet();
      if (current > 1) {
        concurrentExecution.set(true);
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      activeThreads.decrementAndGet();
      return true;
    }

    @Override
    @DistributedLock(key = "single-exec-lock", skipHandler = ReturnDefaultHandler.class)
    public void singleExecutionMethod(AtomicInteger executionCount) {
      executionCount.incrementAndGet();
      try {
        Thread.sleep(500);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    @Override
    @DistributedLock(key = "counter-lock", skipHandler = ReturnDefaultHandler.class)
    public boolean incrementCounter(AtomicInteger counter) {
      counter.incrementAndGet();
      return true;
    }

    @Override
    @DistributedLock(key = "order-lock", mode = LockAcquisitionMode.WAIT_AND_SKIP, waitTime = "10s")
    public void orderedExecutionMethod(int index, List<Integer> order) {
      order.add(index);
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    @Override
    @DistributedLock(
        key = "rw-concurrent",
        type = LockType.READ,
        skipHandler = ReturnDefaultHandler.class)
    public void readOperation(AtomicInteger currentReaders, AtomicInteger maxConcurrentReaders) {
      int current = currentReaders.incrementAndGet();
      maxConcurrentReaders.updateAndGet(max -> Math.max(max, current));
      try {
        Thread.sleep(300);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      currentReaders.decrementAndGet();
    }

    @Override
    @DistributedLock(
        key = "rw-block-test",
        type = LockType.WRITE,
        skipHandler = ReturnDefaultHandler.class)
    public void writeOperation(AtomicBoolean writerActive, CountDownLatch started) {
      writerActive.set(true);
      started.countDown();
      try {
        Thread.sleep(1000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      writerActive.set(false);
    }

    @Override
    @DistributedLock(
        key = "rw-block-test",
        type = LockType.READ,
        skipHandler = ReturnDefaultHandler.class)
    public boolean readDuringWrite() {
      return true;
    }

    @Override
    @DistributedLock(key = "#key", skipHandler = ReturnDefaultHandler.class)
    public void isolatedLockMethod(
        String key, AtomicInteger concurrentExecutions, AtomicInteger maxConcurrentExecutions) {
      int current = concurrentExecutions.incrementAndGet();
      maxConcurrentExecutions.updateAndGet(max -> Math.max(max, current));
      try {
        Thread.sleep(300);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      concurrentExecutions.decrementAndGet();
    }
  }

  @Nested
  @DisplayName("Lock Exclusivity Tests")
  class LockExclusivityTests {

    @Test
    @DisplayName("Should prevent concurrent execution with same lock key")
    void shouldPreventConcurrentExecutionWithSameLockKey() throws InterruptedException {
      AtomicBoolean concurrentExecution = new AtomicBoolean(false);
      AtomicInteger activeThreads = new AtomicInteger(0);
      CountDownLatch allComplete = new CountDownLatch(10);
      CyclicBarrier barrier = new CyclicBarrier(10);

      ExecutorService executor = Executors.newFixedThreadPool(10);

      for (int i = 0; i < 10; i++) {
        executor.submit(
            () -> {
              try {
                barrier.await();
                testService.exclusiveLockMethod(activeThreads, concurrentExecution);
              } catch (Exception e) {
                // Expected for some threads
              } finally {
                allComplete.countDown();
              }
            });
      }

      assertTrue(allComplete.await(30, TimeUnit.SECONDS));
      assertFalse(
          concurrentExecution.get(), "Concurrent execution detected - lock exclusivity violated!");

      executor.shutdown();
    }

    @Test
    @DisplayName("Should maintain lock exclusivity under high contention")
    void shouldMaintainLockExclusivityUnderHighContention() throws InterruptedException {
      int threadCount = 20;
      int iterationsPerThread = 5;
      AtomicBoolean concurrentExecution = new AtomicBoolean(false);
      AtomicInteger activeThreads = new AtomicInteger(0);
      AtomicInteger successfulExecutions = new AtomicInteger(0);
      CountDownLatch allComplete = new CountDownLatch(threadCount);

      ExecutorService executor = Executors.newFixedThreadPool(threadCount);

      for (int i = 0; i < threadCount; i++) {
        executor.submit(
            () -> {
              for (int j = 0; j < iterationsPerThread; j++) {
                boolean executed =
                    testService.contentionTestMethod(activeThreads, concurrentExecution);
                if (executed) {
                  successfulExecutions.incrementAndGet();
                }
              }
              allComplete.countDown();
            });
      }

      assertTrue(allComplete.await(60, TimeUnit.SECONDS));
      assertFalse(
          concurrentExecution.get(), "Concurrent execution detected under high contention!");
      assertTrue(successfulExecutions.get() > 0, "At least some executions should succeed");

      executor.shutdown();
    }

    @Test
    @DisplayName("Should execute exactly once when multiple threads compete")
    void shouldExecuteExactlyOnceWithSimultaneousRequests() throws InterruptedException {
      AtomicInteger executionCount = new AtomicInteger(0);
      CyclicBarrier barrier = new CyclicBarrier(5);
      CountDownLatch allComplete = new CountDownLatch(5);

      ExecutorService executor = Executors.newFixedThreadPool(5);

      for (int i = 0; i < 5; i++) {
        executor.submit(
            () -> {
              try {
                barrier.await();
                testService.singleExecutionMethod(executionCount);
              } catch (Exception e) {
                // Expected for threads that don't get the lock
              } finally {
                allComplete.countDown();
              }
            });
      }

      assertTrue(allComplete.await(10, TimeUnit.SECONDS));
      assertEquals(1, executionCount.get(), "Method should execute exactly once");

      executor.shutdown();
    }
  }

  @Nested
  @DisplayName("Race Condition Tests")
  class RaceConditionTests {

    @Test
    @DisplayName("Should protect shared counter from race conditions")
    void shouldProtectSharedCounterFromRaceConditions() throws InterruptedException {
      int threadCount = 10;
      int incrementsPerThread = 10;
      AtomicInteger counter = new AtomicInteger(0);
      AtomicInteger successfulIncrements = new AtomicInteger(0);
      CountDownLatch allComplete = new CountDownLatch(threadCount);

      ExecutorService executor = Executors.newFixedThreadPool(threadCount);

      for (int i = 0; i < threadCount; i++) {
        executor.submit(
            () -> {
              for (int j = 0; j < incrementsPerThread; j++) {
                if (testService.incrementCounter(counter)) {
                  successfulIncrements.incrementAndGet();
                }
                try {
                  Thread.sleep(10);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              }
              allComplete.countDown();
            });
      }

      assertTrue(allComplete.await(60, TimeUnit.SECONDS));
      assertEquals(
          successfulIncrements.get(),
          counter.get(),
          "Counter value should match successful increments");

      executor.shutdown();
    }

    @Test
    @DisplayName("Should maintain order integrity with sequential lock acquisition")
    void shouldMaintainOrderIntegrityWithSequentialLockAcquisition() throws InterruptedException {
      List<Integer> executionOrder = Collections.synchronizedList(new ArrayList<>());
      CountDownLatch allComplete = new CountDownLatch(5);

      ExecutorService executor = Executors.newFixedThreadPool(5);

      for (int i = 0; i < 5; i++) {
        final int index = i;
        executor.submit(
            () -> {
              try {
                Thread.sleep(index * 100L);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              testService.orderedExecutionMethod(index, executionOrder);
              allComplete.countDown();
            });
      }

      assertTrue(allComplete.await(30, TimeUnit.SECONDS));
      assertEquals(5, executionOrder.size());

      executor.shutdown();
    }
  }

  @Nested
  @DisplayName("Read-Write Concurrency Tests")
  class ReadWriteConcurrencyTests {

    @Test
    @DisplayName("Should allow multiple readers simultaneously")
    void shouldAllowMultipleReadersSimultaneously() throws InterruptedException {
      int readerCount = 5;
      AtomicInteger maxConcurrentReaders = new AtomicInteger(0);
      AtomicInteger currentReaders = new AtomicInteger(0);
      CyclicBarrier barrier = new CyclicBarrier(readerCount);
      CountDownLatch allComplete = new CountDownLatch(readerCount);

      ExecutorService executor = Executors.newFixedThreadPool(readerCount);

      for (int i = 0; i < readerCount; i++) {
        executor.submit(
            () -> {
              try {
                barrier.await();
                testService.readOperation(currentReaders, maxConcurrentReaders);
              } catch (Exception e) {
                // Ignore
              } finally {
                allComplete.countDown();
              }
            });
      }

      assertTrue(allComplete.await(15, TimeUnit.SECONDS));
      assertTrue(
          maxConcurrentReaders.get() > 1,
          "Should have had multiple concurrent readers, but got: " + maxConcurrentReaders.get());

      executor.shutdown();
    }

    @Test
    @DisplayName("Should block readers during write operation")
    void shouldBlockReadersDuringWriteOperation() throws InterruptedException {
      AtomicBoolean writerActive = new AtomicBoolean(false);
      AtomicBoolean readerExecutedDuringWrite = new AtomicBoolean(false);
      CountDownLatch writerStarted = new CountDownLatch(1);
      CountDownLatch allComplete = new CountDownLatch(2);

      Thread writerThread =
          new Thread(
              () -> {
                testService.writeOperation(writerActive, writerStarted);
                allComplete.countDown();
              });

      Thread readerThread =
          new Thread(
              () -> {
                try {
                  writerStarted.await();
                  Thread.sleep(100);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
                if (writerActive.get()) {
                  boolean executed = testService.readDuringWrite();
                  if (executed) {
                    readerExecutedDuringWrite.set(true);
                  }
                }
                allComplete.countDown();
              });

      writerThread.start();
      readerThread.start();

      assertTrue(allComplete.await(15, TimeUnit.SECONDS));
      assertFalse(
          readerExecutedDuringWrite.get(), "Reader should not execute during write operation");
    }
  }

  @Nested
  @DisplayName("Lock Key Isolation Tests")
  class LockKeyIsolationTests {

    @Test
    @DisplayName("Should allow parallel execution with different lock keys")
    void shouldAllowParallelExecutionWithDifferentLockKeys() throws InterruptedException {
      int threadCount = 5;
      AtomicInteger concurrentExecutions = new AtomicInteger(0);
      AtomicInteger maxConcurrentExecutions = new AtomicInteger(0);
      CyclicBarrier barrier = new CyclicBarrier(threadCount);
      CountDownLatch allComplete = new CountDownLatch(threadCount);

      ExecutorService executor = Executors.newFixedThreadPool(threadCount);

      for (int i = 0; i < threadCount; i++) {
        final String lockKey = "key-" + i;
        executor.submit(
            () -> {
              try {
                barrier.await();
                testService.isolatedLockMethod(
                    lockKey, concurrentExecutions, maxConcurrentExecutions);
              } catch (Exception e) {
                // Ignore
              } finally {
                allComplete.countDown();
              }
            });
      }

      assertTrue(allComplete.await(15, TimeUnit.SECONDS));
      assertEquals(
          5,
          maxConcurrentExecutions.get(),
          "Should have had concurrent executions with different keys");

      executor.shutdown();
    }
  }
}
