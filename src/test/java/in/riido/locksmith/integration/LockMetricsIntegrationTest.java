package in.riido.locksmith.integration;

import static org.junit.jupiter.api.Assertions.*;

import in.riido.locksmith.AcquisitionMode;
import in.riido.locksmith.DistributedLock;
import in.riido.locksmith.aspect.DistributedLockAspect;
import in.riido.locksmith.autoconfigure.LocksmithProperties;
import in.riido.locksmith.handler.lock.LockReturnDefaultHandler;
import in.riido.locksmith.metrics.LockMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
@DisplayName("Lock Metrics Integration Tests")
class LockMetricsIntegrationTest {

  private static final int REDIS_PORT = 6379;

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>(DockerImageName.parse("redis:latest")).withExposedPorts(REDIS_PORT);

  private RedissonClient redissonClient;
  private MeterRegistry meterRegistry;
  private LockMetrics lockMetrics;
  private MetricsTestService testService;

  @BeforeEach
  void setUp() {
    Config config = new Config();
    config
        .useSingleServer()
        .setAddress("redis://" + redis.getHost() + ":" + redis.getMappedPort(REDIS_PORT));
    redissonClient = Redisson.create(config);

    meterRegistry = new SimpleMeterRegistry();
    lockMetrics = new LockMetrics(meterRegistry);

    LocksmithProperties properties =
        new LocksmithProperties(
            new LocksmithProperties.LockProperties(
                true, Duration.ofMinutes(1), Duration.ofSeconds(10), "metrics-test:", false, true),
            null,
            null);
    DistributedLockAspect aspect =
        new DistributedLockAspect(
            redissonClient, properties, new GenericApplicationContext(), lockMetrics);

    AspectJProxyFactory factory = new AspectJProxyFactory(new MetricsTestServiceImpl());
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
  @DisplayName("Successful Lock Acquisition Metrics")
  class SuccessfulAcquisitionTests {

    @Test
    @DisplayName("Should record acquired counter on successful lock acquisition")
    void shouldRecordAcquiredCounter() {
      testService.simpleLockedMethod();

      Counter counter = meterRegistry.find("locksmith.lock.acquired").counter();
      assertNotNull(counter);
      assertEquals(1, counter.count());
    }

    @Test
    @DisplayName("Should record acquisition time on successful lock acquisition")
    void shouldRecordAcquisitionTime() {
      testService.simpleLockedMethod();

      Timer timer = meterRegistry.find("locksmith.lock.acquisition.time").timer();
      assertNotNull(timer);
      assertEquals(1, timer.count());
      assertTrue(timer.totalTime(TimeUnit.MILLISECONDS) >= 0);
    }

    @Test
    @DisplayName("Should record held time on successful lock acquisition")
    void shouldRecordHeldTime() throws InterruptedException {
      testService.sleepingMethod(100);

      Timer timer = meterRegistry.find("locksmith.lock.held.time").timer();
      assertNotNull(timer);
      assertEquals(1, timer.count());
      assertTrue(timer.totalTime(TimeUnit.MILLISECONDS) >= 100);
    }

    @Test
    @DisplayName("Should record multiple acquisitions")
    void shouldRecordMultipleAcquisitions() {
      testService.simpleLockedMethod();
      testService.simpleLockedMethod();
      testService.simpleLockedMethod();

      Counter counter = meterRegistry.find("locksmith.lock.acquired").counter();
      assertEquals(3, counter.count());
    }
  }

  @Nested
  @DisplayName("Skipped Lock Acquisition Metrics")
  class SkippedAcquisitionTests {

    @Test
    @DisplayName("Should record skipped counter with immediate reason")
    void shouldRecordSkippedCounterImmediate() throws InterruptedException {
      CountDownLatch lockHeld = new CountDownLatch(1);
      CountDownLatch testComplete = new CountDownLatch(1);

      Thread holdingThread =
          new Thread(
              () -> {
                testService.longHoldingMethod(lockHeld);
                testComplete.countDown();
              });

      holdingThread.start();
      assertTrue(lockHeld.await(5, TimeUnit.SECONDS));

      testService.skipImmediatelyMethod();

      Counter counter =
          meterRegistry.find("locksmith.lock.skipped").tag("reason", "immediate").counter();
      assertNotNull(counter);
      assertEquals(1, counter.count());

      testComplete.await(10, TimeUnit.SECONDS);
    }
  }

  @Nested
  @DisplayName("Auto Renew Gauge Metrics")
  class AutoRenewGaugeTests {

    @Test
    @DisplayName("Should increment and decrement auto renew active gauge")
    void shouldTrackAutoRenewActiveGauge() throws InterruptedException {
      CountDownLatch methodStarted = new CountDownLatch(1);
      CountDownLatch methodComplete = new CountDownLatch(1);
      AtomicBoolean gaugeChecked = new AtomicBoolean(false);

      Thread executingThread =
          new Thread(
              () -> {
                testService.autoRenewMethodWithSignal(methodStarted, gaugeChecked);
                methodComplete.countDown();
              });

      executingThread.start();
      assertTrue(methodStarted.await(5, TimeUnit.SECONDS));

      Gauge gauge = meterRegistry.find("locksmith.lock.autorenew.active").gauge();
      assertNotNull(gauge);
      assertEquals(1, gauge.value());

      gaugeChecked.set(true);
      methodComplete.await(10, TimeUnit.SECONDS);

      assertEquals(0, gauge.value());
    }
  }

  public interface MetricsTestService {
    String simpleLockedMethod();

    void sleepingMethod(long millis) throws InterruptedException;

    void longHoldingMethod(CountDownLatch lockHeld);

    String skipImmediatelyMethod();

    void autoRenewMethodWithSignal(CountDownLatch started, AtomicBoolean waitForCheck);
  }

  public static class MetricsTestServiceImpl implements MetricsTestService {

    @Override
    @DistributedLock(key = "metrics-simple")
    public String simpleLockedMethod() {
      return "executed";
    }

    @Override
    @DistributedLock(key = "metrics-sleep")
    public void sleepingMethod(long millis) throws InterruptedException {
      Thread.sleep(millis);
    }

    @Override
    @DistributedLock(key = "metrics-hold", leaseTime = "30s")
    public void longHoldingMethod(CountDownLatch lockHeld) {
      lockHeld.countDown();
      try {
        Thread.sleep(2000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    @Override
    @DistributedLock(
        key = "metrics-hold",
        mode = AcquisitionMode.SKIP_IMMEDIATELY,
        skipHandler = LockReturnDefaultHandler.class)
    public String skipImmediatelyMethod() {
      return "executed";
    }

    @Override
    @DistributedLock(key = "metrics-autorenew", autoRenew = true)
    public void autoRenewMethodWithSignal(CountDownLatch started, AtomicBoolean waitForCheck) {
      started.countDown();
      while (!waitForCheck.get()) {
        try {
          Thread.sleep(50);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    }
  }
}
