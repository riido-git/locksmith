package in.riido.locksmith.integration;

import static org.junit.jupiter.api.Assertions.*;

import in.riido.locksmith.aspect.DistributedLockAspect;
import in.riido.locksmith.autoconfigure.LocksmithProperties;
import in.riido.locksmith.exception.LockNotAcquiredException;
import in.riido.locksmith.integration.service.IntegrationTestService;
import in.riido.locksmith.integration.service.IntegrationTestServiceImpl;
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
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@ExtendWith(DockerAvailableCondition.class)
@DisplayName("Distributed Lock Integration Tests")
class DistributedLockIntegrationTest {

  private static final int REDIS_PORT = 6379;

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>(DockerImageName.parse("redis:latest")).withExposedPorts(REDIS_PORT);

  private RedissonClient redissonClient;
  private IntegrationTestService testService;

  @BeforeEach
  void setUp() {
    Config config = new Config();
    config
        .useSingleServer()
        .setAddress("redis://" + redis.getHost() + ":" + redis.getMappedPort(REDIS_PORT));
    redissonClient = Redisson.create(config);

    LocksmithProperties properties =
        new LocksmithProperties(Duration.ofMinutes(1), Duration.ofSeconds(10), "test:", false);
    DistributedLockAspect aspect = new DistributedLockAspect(redissonClient, properties);

    // Use CGLIB proxy on the implementation class directly to preserve annotations
    AspectJProxyFactory factory = new AspectJProxyFactory(new IntegrationTestServiceImpl());
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
  @DisplayName("Basic Lock Acquisition Tests")
  class BasicLockAcquisitionTests {

    @Test
    @DisplayName("Should acquire lock and execute method")
    void shouldAcquireLockAndExecuteMethod() {
      String result = testService.simpleLockedMethod();

      assertEquals("executed", result);
    }

    @Test
    @DisplayName("Should release lock after method execution")
    void shouldReleaseLockAfterMethodExecution() {
      testService.simpleLockedMethod();
      String result = testService.simpleLockedMethod();

      assertEquals("executed", result);
    }

    @Test
    @DisplayName("Should resolve SpEL key from method parameter")
    void shouldResolveSpelKeyFromMethodParameter() {
      String result = testService.lockedMethodWithSpelKey("user123");

      assertEquals("processed:user123", result);
    }

    @Test
    @DisplayName("Should allow concurrent execution with different lock keys")
    void shouldAllowConcurrentExecutionWithDifferentLockKeys() throws InterruptedException {
      AtomicInteger completedCount = new AtomicInteger(0);
      CountDownLatch latch = new CountDownLatch(2);

      Thread thread1 =
          new Thread(
              () -> {
                testService.lockedMethodWithSpelKey("user1");
                completedCount.incrementAndGet();
                latch.countDown();
              });

      Thread thread2 =
          new Thread(
              () -> {
                testService.lockedMethodWithSpelKey("user2");
                completedCount.incrementAndGet();
                latch.countDown();
              });

      thread1.start();
      thread2.start();

      assertTrue(latch.await(10, TimeUnit.SECONDS));
      assertEquals(2, completedCount.get());
    }
  }

  @Nested
  @DisplayName("Lock Contention Tests")
  class LockContentionTests {

    @Test
    @DisplayName("Should skip execution when lock is held with SKIP_IMMEDIATELY mode")
    void shouldSkipExecutionWhenLockIsHeldWithSkipImmediately() throws InterruptedException {
      AtomicInteger executionCount = new AtomicInteger(0);
      CountDownLatch firstThreadStarted = new CountDownLatch(1);
      CountDownLatch firstThreadComplete = new CountDownLatch(1);

      Thread holdingThread =
          new Thread(
              () -> {
                testService.longRunningMethod(firstThreadStarted, executionCount);
                firstThreadComplete.countDown();
              });

      holdingThread.start();
      assertTrue(firstThreadStarted.await(5, TimeUnit.SECONDS));

      assertNull(testService.tryAcquireSameLock());

      firstThreadComplete.await(10, TimeUnit.SECONDS);
      assertEquals(1, executionCount.get());
    }

    @Test
    @DisplayName("Should throw exception when lock is not acquired with ThrowExceptionHandler")
    void shouldThrowExceptionWhenLockNotAcquired() throws InterruptedException {
      CountDownLatch firstThreadStarted = new CountDownLatch(1);
      CountDownLatch testComplete = new CountDownLatch(1);

      Thread holdingThread =
          new Thread(
              () -> {
                try {
                  testService.holdLockForThrowTest(firstThreadStarted);
                } finally {
                  testComplete.countDown();
                }
              });

      holdingThread.start();
      assertTrue(firstThreadStarted.await(5, TimeUnit.SECONDS));

      assertThrows(LockNotAcquiredException.class, () -> testService.throwOnLockNotAcquired());

      testComplete.await(10, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("Should wait and acquire lock with WAIT_AND_SKIP mode")
    void shouldWaitAndAcquireLockWithWaitAndSkipMode() throws InterruptedException {
      AtomicInteger executionCount = new AtomicInteger(0);
      CountDownLatch bothComplete = new CountDownLatch(2);

      Thread thread1 =
          new Thread(
              () -> {
                testService.shortHoldingMethod(executionCount);
                bothComplete.countDown();
              });

      Thread thread2 =
          new Thread(
              () -> {
                try {
                  Thread.sleep(50);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
                testService.waitAndExecuteMethod(executionCount);
                bothComplete.countDown();
              });

      thread1.start();
      thread2.start();

      assertTrue(bothComplete.await(15, TimeUnit.SECONDS));
      assertEquals(2, executionCount.get());
    }
  }

  @Nested
  @DisplayName("Read-Write Lock Tests")
  class ReadWriteLockTests {

    @Test
    @DisplayName("Should allow multiple concurrent read locks")
    void shouldAllowMultipleConcurrentReadLocks() throws InterruptedException {
      AtomicInteger concurrentReaders = new AtomicInteger(0);
      AtomicInteger maxConcurrentReaders = new AtomicInteger(0);
      CountDownLatch allStarted = new CountDownLatch(3);
      CountDownLatch allComplete = new CountDownLatch(3);

      ExecutorService executor = Executors.newFixedThreadPool(3);

      for (int i = 0; i < 3; i++) {
        executor.submit(
            () -> {
              testService.readLockMethod(concurrentReaders, maxConcurrentReaders, allStarted);
              allComplete.countDown();
            });
      }

      assertTrue(allComplete.await(15, TimeUnit.SECONDS));
      assertTrue(maxConcurrentReaders.get() > 1, "Expected multiple concurrent readers");

      executor.shutdown();
    }

    @Test
    @DisplayName("Should block write lock when read lock is held")
    void shouldBlockWriteLockWhenReadLockIsHeld() throws InterruptedException {
      CountDownLatch readLockAcquired = new CountDownLatch(1);
      CountDownLatch readLockReleased = new CountDownLatch(1);
      AtomicInteger writeExecuted = new AtomicInteger(0);

      Thread readerThread =
          new Thread(
              () -> {
                testService.holdReadLock(readLockAcquired);
                readLockReleased.countDown();
              });

      readerThread.start();
      assertTrue(readLockAcquired.await(5, TimeUnit.SECONDS));

      assertNull(testService.tryWriteLock(writeExecuted));
      assertEquals(0, writeExecuted.get());

      readLockReleased.await(10, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("Should block read lock when write lock is held")
    void shouldBlockReadLockWhenWriteLockIsHeld() throws InterruptedException {
      CountDownLatch writeLockAcquired = new CountDownLatch(1);
      CountDownLatch writeLockReleased = new CountDownLatch(1);
      AtomicInteger readExecuted = new AtomicInteger(0);

      Thread writerThread =
          new Thread(
              () -> {
                testService.holdWriteLock(writeLockAcquired);
                writeLockReleased.countDown();
              });

      writerThread.start();
      assertTrue(writeLockAcquired.await(5, TimeUnit.SECONDS));

      assertNull(testService.tryReadLock(readExecuted));
      assertEquals(0, readExecuted.get());

      writeLockReleased.await(10, TimeUnit.SECONDS);
    }
  }

  @Nested
  @DisplayName("Auto Renew Tests")
  class AutoRenewTests {

    @Test
    @DisplayName("Should auto-renew lock for long-running method")
    void shouldAutoRenewLockForLongRunningMethod() {
      long startTime = System.currentTimeMillis();
      String result = testService.autoRenewMethod();
      long duration = System.currentTimeMillis() - startTime;

      assertEquals("completed", result);
      assertTrue(duration >= 2000, "Method should have run for at least 2 seconds");
    }
  }
}
