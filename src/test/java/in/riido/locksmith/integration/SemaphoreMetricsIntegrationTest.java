package in.riido.locksmith.integration;

import static org.junit.jupiter.api.Assertions.*;

import in.riido.locksmith.AcquisitionMode;
import in.riido.locksmith.DistributedSemaphore;
import in.riido.locksmith.aspect.DistributedSemaphoreAspect;
import in.riido.locksmith.autoconfigure.LocksmithProperties;
import in.riido.locksmith.handler.semaphore.SemaphoreReturnDefaultHandler;
import in.riido.locksmith.metrics.SemaphoreMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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

@Testcontainers
@ExtendWith(DockerAvailableCondition.class)
@DisplayName("Semaphore Metrics Integration Tests")
class SemaphoreMetricsIntegrationTest {

  private static final int REDIS_PORT = 6379;

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>(DockerImageName.parse("redis:latest")).withExposedPorts(REDIS_PORT);

  private RedissonClient redissonClient;
  private MeterRegistry meterRegistry;
  private SemaphoreMetrics semaphoreMetrics;
  private SemaphoreMetricsTestService testService;

  @BeforeEach
  void setUp() {
    Config config = new Config();
    config
        .useSingleServer()
        .setAddress("redis://" + redis.getHost() + ":" + redis.getMappedPort(REDIS_PORT));
    redissonClient = Redisson.create(config);

    meterRegistry = new SimpleMeterRegistry();
    semaphoreMetrics = new SemaphoreMetrics(meterRegistry);

    LocksmithProperties properties =
        new LocksmithProperties(
            null,
            new LocksmithProperties.SemaphoreProperties(
                Duration.ofMinutes(1), Duration.ofSeconds(10), "semaphore-metrics:", false, true),
            null);
    DistributedSemaphoreAspect aspect =
        new DistributedSemaphoreAspect(
            redissonClient, properties, new GenericApplicationContext(), semaphoreMetrics);

    AspectJProxyFactory factory = new AspectJProxyFactory(new SemaphoreMetricsTestServiceImpl());
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
  @DisplayName("Successful Permit Acquisition Metrics")
  class SuccessfulAcquisitionTests {

    @Test
    @DisplayName("Should record acquired counter on successful permit acquisition")
    void shouldRecordAcquiredCounter() {
      testService.simpleMethod();

      Counter counter = meterRegistry.find("locksmith.semaphore.acquired").counter();
      assertNotNull(counter);
      assertEquals(1, counter.count());
    }

    @Test
    @DisplayName("Should record acquisition time on successful permit acquisition")
    void shouldRecordAcquisitionTime() {
      testService.simpleMethod();

      Timer timer = meterRegistry.find("locksmith.semaphore.acquisition.time").timer();
      assertNotNull(timer);
      assertEquals(1, timer.count());
      assertTrue(timer.totalTime(TimeUnit.MILLISECONDS) >= 0);
    }

    @Test
    @DisplayName("Should record held time on successful permit acquisition")
    void shouldRecordHeldTime() throws InterruptedException {
      testService.sleepingMethod(100);

      Timer timer = meterRegistry.find("locksmith.semaphore.held.time").timer();
      assertNotNull(timer);
      assertEquals(1, timer.count());
      assertTrue(timer.totalTime(TimeUnit.MILLISECONDS) >= 100);
    }

    @Test
    @DisplayName("Should record multiple acquisitions")
    void shouldRecordMultipleAcquisitions() {
      testService.simpleMethod();
      testService.simpleMethod();
      testService.simpleMethod();

      Counter counter = meterRegistry.find("locksmith.semaphore.acquired").counter();
      assertEquals(3, counter.count());
    }
  }

  @Nested
  @DisplayName("Skipped Permit Acquisition Metrics")
  class SkippedAcquisitionTests {

    @Test
    @DisplayName("Should record skipped counter when all permits are taken")
    void shouldRecordSkippedCounter() throws InterruptedException {
      CountDownLatch permitHeld = new CountDownLatch(1);
      CountDownLatch testComplete = new CountDownLatch(1);

      Thread holdingThread =
          new Thread(
              () -> {
                testService.singlePermitHoldingMethod(permitHeld);
                testComplete.countDown();
              });

      holdingThread.start();
      assertTrue(permitHeld.await(5, TimeUnit.SECONDS));

      testService.skipImmediatelyMethod();

      Counter counter =
          meterRegistry.find("locksmith.semaphore.skipped").tag("reason", "immediate").counter();
      assertNotNull(counter);
      assertEquals(1, counter.count());

      testComplete.await(10, TimeUnit.SECONDS);
    }
  }

  public interface SemaphoreMetricsTestService {
    String simpleMethod();

    void sleepingMethod(long millis) throws InterruptedException;

    void singlePermitHoldingMethod(CountDownLatch permitHeld);

    String skipImmediatelyMethod();
  }

  public static class SemaphoreMetricsTestServiceImpl implements SemaphoreMetricsTestService {

    @Override
    @DistributedSemaphore(key = "semaphore-metrics-simple", permits = 5)
    public String simpleMethod() {
      return "executed";
    }

    @Override
    @DistributedSemaphore(key = "semaphore-metrics-sleep", permits = 5)
    public void sleepingMethod(long millis) throws InterruptedException {
      Thread.sleep(millis);
    }

    @Override
    @DistributedSemaphore(key = "semaphore-metrics-single", permits = 1, leaseTime = "30s")
    public void singlePermitHoldingMethod(CountDownLatch permitHeld) {
      permitHeld.countDown();
      try {
        Thread.sleep(2000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    @Override
    @DistributedSemaphore(
        key = "semaphore-metrics-single",
        permits = 1,
        mode = AcquisitionMode.SKIP_IMMEDIATELY,
        skipHandler = SemaphoreReturnDefaultHandler.class)
    public String skipImmediatelyMethod() {
      return "executed";
    }
  }
}
