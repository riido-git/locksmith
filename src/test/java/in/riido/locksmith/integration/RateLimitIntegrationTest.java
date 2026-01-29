package in.riido.locksmith.integration;

import static org.junit.jupiter.api.Assertions.*;

import in.riido.locksmith.aspect.RateLimitAspect;
import in.riido.locksmith.autoconfigure.LocksmithProperties;
import in.riido.locksmith.exception.RateLimitExceededException;
import in.riido.locksmith.integration.service.RateLimitIntegrationTestService;
import in.riido.locksmith.integration.service.RateLimitIntegrationTestServiceImpl;
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
import org.springframework.context.support.GenericApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Integration tests for RateLimit annotation.
 *
 * @author Garvit Joshi
 * @since 2.1.0
 */
@Testcontainers
@ExtendWith(DockerAvailableCondition.class)
@DisplayName("Rate Limit Integration Tests")
class RateLimitIntegrationTest {

  private static final int REDIS_PORT = 6379;

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>(DockerImageName.parse("redis:latest")).withExposedPorts(REDIS_PORT);

  private RedissonClient redissonClient;
  private RateLimitIntegrationTestService testService;

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
            null,
            new LocksmithProperties.RateLimitProperties(
                true, Duration.ofMinutes(1), "ratelimit-test:", false, false));
    RateLimitAspect aspect =
        new RateLimitAspect(redissonClient, properties, new GenericApplicationContext());

    AspectJProxyFactory factory =
        new AspectJProxyFactory(new RateLimitIntegrationTestServiceImpl());
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
  @DisplayName("Basic Rate Limit Tests")
  class BasicRateLimitTests {

    @Test
    @DisplayName("Should acquire rate limit and execute method")
    void shouldAcquireRateLimitAndExecuteMethod() {
      String result = testService.simpleRateLimitedMethod();
      assertEquals("executed", result);
    }

    @Test
    @DisplayName("Should allow multiple executions within rate limit")
    void shouldAllowMultipleExecutionsWithinRateLimit() {
      // 10 permits per second, call 5 times
      for (int i = 0; i < 5; i++) {
        String result = testService.simpleRateLimitedMethod();
        assertEquals("executed", result);
      }
    }

    @Test
    @DisplayName("Should resolve SpEL key from method parameter")
    void shouldResolveSpelKeyFromMethodParameter() {
      String result = testService.rateLimitedMethodWithSpelKey("resource-123");
      assertEquals("processed:resource-123", result);
    }
  }

  @Nested
  @DisplayName("Rate Limit Enforcement Tests")
  class RateLimitEnforcementTests {

    @Test
    @DisplayName("Should throw exception when rate limit exceeded")
    void shouldThrowExceptionWhenRateLimitExceeded() {
      // First call should succeed (1 permit per 10 seconds)
      String result = testService.throwOnLimitExceeded();
      assertEquals("executed", result);

      // Second call should fail
      assertThrows(RateLimitExceededException.class, () -> testService.throwOnLimitExceeded());
    }

    @Test
    @DisplayName("Should return default value when rate limit exceeded with ReturnDefaultHandler")
    void shouldReturnDefaultWhenRateLimitExceeded() {
      // First call should succeed
      String result1 = testService.returnDefaultOnLimitExceeded();
      assertEquals("executed", result1);

      // Second call should return null (default for String)
      String result2 = testService.returnDefaultOnLimitExceeded();
      assertNull(result2);
    }

    @Test
    @DisplayName("Should limit executions with SKIP_IMMEDIATELY mode")
    void shouldLimitExecutionsWithSkipImmediately() {
      AtomicInteger counter = new AtomicInteger(0);

      // Try to execute 20 times, only 5 should succeed (5 permits per second)
      for (int i = 0; i < 20; i++) {
        testService.trackingMethod(counter);
      }

      assertTrue(counter.get() <= 5, "Should not exceed 5 executions, but got: " + counter.get());
      assertTrue(counter.get() > 0, "At least some executions should succeed");
    }
  }

  @Nested
  @DisplayName("Wait Mode Tests")
  class WaitModeTests {

    @Test
    @DisplayName("Should wait and acquire when using WAIT_AND_SKIP mode")
    void shouldWaitAndAcquireWithWaitMode() throws InterruptedException {
      int threadCount = 6;
      AtomicInteger counter = new AtomicInteger(0);
      CountDownLatch latch = new CountDownLatch(threadCount);

      ExecutorService executor = Executors.newFixedThreadPool(threadCount);

      for (int i = 0; i < threadCount; i++) {
        executor.submit(
            () -> {
              try {
                testService.waitAndAcquireMethod(counter);
              } finally {
                latch.countDown();
              }
            });
      }

      // Should complete within timeout since wait mode allows waiting
      assertTrue(latch.await(30, TimeUnit.SECONDS));
      assertEquals(threadCount, counter.get(), "All threads should eventually execute");

      executor.shutdown();
    }
  }

  @Nested
  @DisplayName("Key Isolation Tests")
  class KeyIsolationTests {

    @Test
    @DisplayName("Should allow parallel execution with different keys")
    void shouldAllowParallelExecutionWithDifferentKeys() throws InterruptedException {
      int keyCount = 5;
      AtomicInteger totalCounter = new AtomicInteger(0);
      CountDownLatch latch = new CountDownLatch(keyCount * 5);

      ExecutorService executor = Executors.newFixedThreadPool(keyCount * 5);

      for (int k = 0; k < keyCount; k++) {
        final String key = "isolated-key-" + k;
        for (int i = 0; i < 5; i++) {
          executor.submit(
              () -> {
                try {
                  testService.isolatedKeyMethod(key, totalCounter);
                } finally {
                  latch.countDown();
                }
              });
        }
      }

      assertTrue(latch.await(30, TimeUnit.SECONDS));
      assertEquals(keyCount * 5, totalCounter.get(), "All executions should complete");

      executor.shutdown();
    }
  }

  @Nested
  @DisplayName("Concurrent Access Tests")
  class ConcurrentAccessTests {

    @Test
    @DisplayName("Should handle high concurrency gracefully")
    void shouldHandleHighConcurrency() throws InterruptedException {
      int threadCount = 50;
      AtomicInteger successCount = new AtomicInteger(0);
      CountDownLatch latch = new CountDownLatch(threadCount);

      ExecutorService executor = Executors.newFixedThreadPool(threadCount);

      for (int i = 0; i < threadCount; i++) {
        executor.submit(
            () -> {
              try {
                testService.shortWorkMethod(successCount);
              } finally {
                latch.countDown();
              }
            });
      }

      assertTrue(latch.await(60, TimeUnit.SECONDS));
      assertEquals(threadCount, successCount.get(), "All threads should complete with wait mode");

      executor.shutdown();
    }
  }

  @Nested
  @DisplayName("Exception Handling Tests")
  class ExceptionHandlingTests {

    @Test
    @DisplayName("RateLimitExceededException should contain key and method name")
    void exceptionShouldContainKeyAndMethodName() {
      // First call succeeds
      testService.throwOnLimitExceeded();

      // Second call throws
      RateLimitExceededException exception =
          assertThrows(RateLimitExceededException.class, () -> testService.throwOnLimitExceeded());

      assertNotNull(exception.getRateLimitKey());
      assertNotNull(exception.getMethodName());
      assertTrue(exception.getMessage().contains(exception.getRateLimitKey()));
    }
  }
}
