package in.riido.locksmith.integration;

import static org.junit.jupiter.api.Assertions.*;

import in.riido.locksmith.DistributedLock;
import in.riido.locksmith.LockAcquisitionMode;
import in.riido.locksmith.aspect.DistributedLockAspect;
import in.riido.locksmith.autoconfigure.LocksmithProperties;
import in.riido.locksmith.handler.ReturnDefaultHandler;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@ExtendWith(DockerAvailableCondition.class)
@DisplayName("Stress and Performance Tests")
class StressPerformanceTest {

  private static final Logger LOG = LoggerFactory.getLogger(StressPerformanceTest.class);
  private static final int REDIS_PORT = 6379;

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>(DockerImageName.parse("redis:latest")).withExposedPorts(REDIS_PORT);

  private RedissonClient redissonClient;
  private StressTestService testService;

  @BeforeEach
  void setUp() {
    Config config = new Config();
    config
        .useSingleServer()
        .setAddress("redis://" + redis.getHost() + ":" + redis.getMappedPort(REDIS_PORT));
    redissonClient = Redisson.create(config);

    LocksmithProperties properties =
        new LocksmithProperties(Duration.ofMinutes(1), Duration.ofSeconds(30), "stress:");
    DistributedLockAspect aspect = new DistributedLockAspect(redissonClient, properties);

    // Use CGLIB proxy on the implementation class directly to preserve annotations
    AspectJProxyFactory factory = new AspectJProxyFactory(new StressTestServiceImpl());
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
  @DisplayName("Load Tests")
  class LoadTests {

    @Test
    @DisplayName("Should handle high request volume with single lock")
    void shouldHandleHighRequestVolumeWithSingleLock() throws InterruptedException {
      int threadCount = 50;
      int requestsPerThread = 20;
      AtomicInteger successCount = new AtomicInteger(0);
      AtomicInteger skipCount = new AtomicInteger(0);
      CountDownLatch allComplete = new CountDownLatch(threadCount);

      ExecutorService executor = Executors.newFixedThreadPool(threadCount);

      long startTime = System.currentTimeMillis();

      for (int i = 0; i < threadCount; i++) {
        executor.submit(
            () -> {
              for (int j = 0; j < requestsPerThread; j++) {
                boolean result = testService.highVolumeMethod();
                if (result) {
                  successCount.incrementAndGet();
                } else {
                  skipCount.incrementAndGet();
                }
              }
              allComplete.countDown();
            });
      }

      assertTrue(allComplete.await(120, TimeUnit.SECONDS));
      long duration = System.currentTimeMillis() - startTime;

      int totalRequests = threadCount * requestsPerThread;
      LOG.info(
          "High volume test completed: {} requests in {}ms, {} successful, {} skipped",
          totalRequests,
          duration,
          successCount.get(),
          skipCount.get());

      assertTrue(successCount.get() > 0, "At least some requests should succeed");
      assertEquals(
          totalRequests,
          successCount.get() + skipCount.get(),
          "All requests should be accounted for");

      executor.shutdown();
    }

    @Test
    @DisplayName("Should handle concurrent operations across multiple lock keys")
    void shouldHandleConcurrentOperationsAcrossMultipleLockKeys() throws InterruptedException {
      int lockKeyCount = 10;
      int threadsPerKey = 10;
      int operationsPerThread = 10;
      AtomicInteger totalOperations = new AtomicInteger(0);
      CountDownLatch allComplete = new CountDownLatch(lockKeyCount * threadsPerKey);

      ExecutorService executor = Executors.newFixedThreadPool(lockKeyCount * threadsPerKey);

      long startTime = System.currentTimeMillis();

      for (int k = 0; k < lockKeyCount; k++) {
        final String lockKey = "key-" + k;
        for (int t = 0; t < threadsPerKey; t++) {
          executor.submit(
              () -> {
                for (int o = 0; o < operationsPerThread; o++) {
                  testService.multiKeyMethod(lockKey);
                  totalOperations.incrementAndGet();
                }
                allComplete.countDown();
              });
        }
      }

      assertTrue(allComplete.await(120, TimeUnit.SECONDS));
      long duration = System.currentTimeMillis() - startTime;

      int expectedOperations = lockKeyCount * threadsPerKey * operationsPerThread;
      LOG.info(
          "Multi-key test completed: {} operations in {}ms, throughput: {}/sec",
          totalOperations.get(),
          duration,
          (totalOperations.get() * 1000L) / duration);

      assertEquals(expectedOperations, totalOperations.get());

      executor.shutdown();
    }
  }

  @Nested
  @DisplayName("Performance Benchmark Tests")
  class PerformanceBenchmarkTests {

    @Test
    @DisplayName("Should measure lock acquisition latency")
    void shouldMeasureLockAcquisitionLatency() throws InterruptedException {
      int iterations = 100;
      ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();

      for (int i = 0; i < iterations; i++) {
        long start = System.nanoTime();
        testService.latencyMeasureMethod();
        long latency = System.nanoTime() - start;
        latencies.add(latency);
      }

      List<Long> sortedLatencies = new ArrayList<>(latencies);
      sortedLatencies.sort(Long::compareTo);

      LongSummaryStatistics stats =
          sortedLatencies.stream().mapToLong(Long::longValue).summaryStatistics();

      long p50 = sortedLatencies.get(sortedLatencies.size() / 2);
      long p95 = sortedLatencies.get((int) (sortedLatencies.size() * 0.95));
      long p99 = sortedLatencies.get((int) (sortedLatencies.size() * 0.99));

      LOG.info(
          "Lock acquisition latency (ns) - Min: {}, Max: {}, Avg: {}, P50: {}, P95: {}, P99: {}",
          stats.getMin(),
          stats.getMax(),
          (long) stats.getAverage(),
          p50,
          p95,
          p99);

      assertTrue(stats.getAverage() < 100_000_000, "Average latency should be under 100ms");
    }

    @Test
    @DisplayName("Should measure throughput under contention")
    void shouldMeasureThroughputUnderContention() throws InterruptedException {
      int threadCount = 10;
      int durationSeconds = 5;
      AtomicInteger operationCount = new AtomicInteger(0);
      AtomicLong totalLatency = new AtomicLong(0);
      CountDownLatch startSignal = new CountDownLatch(1);
      CountDownLatch allComplete = new CountDownLatch(threadCount);

      ExecutorService executor = Executors.newFixedThreadPool(threadCount);

      for (int i = 0; i < threadCount; i++) {
        executor.submit(
            () -> {
              try {
                startSignal.await();
                long endTime = System.currentTimeMillis() + (durationSeconds * 1000L);
                while (System.currentTimeMillis() < endTime) {
                  long start = System.nanoTime();
                  testService.throughputMethod();
                  totalLatency.addAndGet(System.nanoTime() - start);
                  operationCount.incrementAndGet();
                }
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } finally {
                allComplete.countDown();
              }
            });
      }

      startSignal.countDown();
      assertTrue(allComplete.await(durationSeconds + 10, TimeUnit.SECONDS));

      double throughput = operationCount.get() / (double) durationSeconds;
      double avgLatencyMs = (totalLatency.get() / (double) operationCount.get()) / 1_000_000;

      LOG.info(
          "Throughput test: {} operations in {}s, throughput: {}/sec, avg latency: {}ms",
          operationCount.get(),
          durationSeconds,
          throughput,
          avgLatencyMs);

      assertTrue(operationCount.get() > 0, "Should complete some operations");

      executor.shutdown();
    }
  }

  @Nested
  @DisplayName("Resource Leak Tests")
  class ResourceLeakTests {

    @Test
    @DisplayName("Should not leak resources during extended operation")
    void shouldNotLeakResourcesDuringExtendedOperation() throws InterruptedException {
      int iterations = 200;
      AtomicInteger successCount = new AtomicInteger(0);

      Runtime runtime = Runtime.getRuntime();
      runtime.gc();
      long initialMemory = runtime.totalMemory() - runtime.freeMemory();

      for (int i = 0; i < iterations; i++) {
        testService.resourceLeakTestMethod();
        successCount.incrementAndGet();

        if (i % 50 == 0 && i > 0) {
          runtime.gc();
          long currentMemory = runtime.totalMemory() - runtime.freeMemory();
          long memoryIncrease = currentMemory - initialMemory;
          LOG.debug("Iteration {}: Memory increase: {} bytes", i, memoryIncrease);
        }
      }

      runtime.gc();
      Thread.sleep(100);
      long finalMemory = runtime.totalMemory() - runtime.freeMemory();
      long memoryIncrease = finalMemory - initialMemory;

      LOG.info(
          "Resource leak test: {} iterations, memory increase: {} bytes ({} MB)",
          iterations,
          memoryIncrease,
          memoryIncrease / (1024 * 1024));

      assertEquals(iterations, successCount.get());
      assertTrue(memoryIncrease < 50 * 1024 * 1024, "Memory should not increase by more than 50MB");
    }

    @Test
    @DisplayName("Should properly release locks after exceptions")
    void shouldProperlyReleaseLocksAfterExceptions() throws InterruptedException {
      int iterations = 50;
      AtomicInteger exceptionCount = new AtomicInteger(0);
      AtomicInteger successAfterExceptionCount = new AtomicInteger(0);

      for (int i = 0; i < iterations; i++) {
        try {
          testService.exceptionThrowingMethod();
        } catch (RuntimeException e) {
          exceptionCount.incrementAndGet();
        }

        boolean acquired = testService.lockAfterExceptionMethod();
        if (acquired) {
          successAfterExceptionCount.incrementAndGet();
        }
      }

      LOG.info(
          "Exception release test: {} exceptions, {} successful acquisitions after",
          exceptionCount.get(),
          successAfterExceptionCount.get());

      assertEquals(iterations, exceptionCount.get(), "All iterations should throw exceptions");
      assertEquals(
          iterations,
          successAfterExceptionCount.get(),
          "All lock acquisitions after exceptions should succeed");
    }
  }

  @Nested
  @DisplayName("Sustained Load Tests")
  class SustainedLoadTests {

    @Test
    @DisplayName("Should handle sustained load without degradation")
    void shouldHandleSustainedLoadWithoutDegradation() throws InterruptedException {
      int threadCount = 5;
      int phaseDurationSeconds = 3;
      int phaseCount = 3;
      List<Double> phaseThroughputs = new ArrayList<>();

      ExecutorService executor = Executors.newFixedThreadPool(threadCount);

      for (int phase = 0; phase < phaseCount; phase++) {
        AtomicInteger operationCount = new AtomicInteger(0);
        CountDownLatch startSignal = new CountDownLatch(1);
        CountDownLatch phaseComplete = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
          executor.submit(
              () -> {
                try {
                  startSignal.await();
                  long endTime = System.currentTimeMillis() + (phaseDurationSeconds * 1000L);
                  while (System.currentTimeMillis() < endTime) {
                    testService.sustainedLoadMethod();
                    operationCount.incrementAndGet();
                  }
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                } finally {
                  phaseComplete.countDown();
                }
              });
        }

        startSignal.countDown();
        assertTrue(phaseComplete.await(phaseDurationSeconds + 5, TimeUnit.SECONDS));

        double throughput = operationCount.get() / (double) phaseDurationSeconds;
        phaseThroughputs.add(throughput);
        LOG.info("Phase {} throughput: {}/sec", phase + 1, throughput);

        Thread.sleep(500);
      }

      double avgThroughput =
          phaseThroughputs.stream().mapToDouble(Double::doubleValue).average().orElse(0);
      double minThroughput =
          phaseThroughputs.stream().mapToDouble(Double::doubleValue).min().orElse(0);

      LOG.info(
          "Sustained load test: avg throughput {}/sec, min throughput {}/sec",
          avgThroughput,
          minThroughput);

      assertTrue(
          minThroughput >= avgThroughput * 0.7, "Throughput should not degrade by more than 30%");

      executor.shutdown();
    }
  }

  public interface StressTestService {
    boolean highVolumeMethod();

    void multiKeyMethod(String key);

    void latencyMeasureMethod();

    void throughputMethod();

    void resourceLeakTestMethod();

    void exceptionThrowingMethod();

    boolean lockAfterExceptionMethod();

    void sustainedLoadMethod();
  }

  public static class StressTestServiceImpl implements StressTestService {

    @Override
    @DistributedLock(key = "high-volume", skipHandler = ReturnDefaultHandler.class)
    public boolean highVolumeMethod() {
      return true;
    }

    @Override
    @DistributedLock(key = "#key", mode = LockAcquisitionMode.WAIT_AND_SKIP, waitTime = "5s")
    public void multiKeyMethod(String key) {
      // Minimal work
    }

    @Override
    @DistributedLock(key = "latency-test")
    public void latencyMeasureMethod() {
      // Minimal work to measure lock overhead
    }

    @Override
    @DistributedLock(
        key = "throughput-test",
        mode = LockAcquisitionMode.WAIT_AND_SKIP,
        waitTime = "10s")
    public void throughputMethod() {
      // Minimal work
    }

    @Override
    @DistributedLock(key = "resource-leak-test")
    public void resourceLeakTestMethod() {
      // Minimal work
    }

    @Override
    @DistributedLock(key = "exception-test")
    public void exceptionThrowingMethod() {
      throw new RuntimeException("Test exception");
    }

    @Override
    @DistributedLock(key = "exception-test", skipHandler = ReturnDefaultHandler.class)
    public boolean lockAfterExceptionMethod() {
      return true;
    }

    @Override
    @DistributedLock(
        key = "sustained-load",
        mode = LockAcquisitionMode.WAIT_AND_SKIP,
        waitTime = "5s")
    public void sustainedLoadMethod() {
      // Minimal work
    }
  }
}
