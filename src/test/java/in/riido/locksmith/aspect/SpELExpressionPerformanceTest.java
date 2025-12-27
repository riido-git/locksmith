package in.riido.locksmith.aspect;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import in.riido.locksmith.DistributedLock;
import in.riido.locksmith.autoconfigure.LocksmithProperties;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Performance stress tests for SpEL expression evaluation in DistributedLockAspect.
 *
 * <p>These tests demonstrate the performance impact of repeatedly parsing SpEL expressions without
 * caching. The results can be used to justify and measure the effectiveness of implementing
 * expression caching as described in GitHub Issue #28.
 *
 * @see <a href="https://github.com/riido-git/locksmith/issues/28">GitHub Issue #28 - Performance
 *     optimization: SpEL expression caching</a>
 */
@DisplayName("SpEL Expression Performance Tests (Issue #28)")
class SpELExpressionPerformanceTest {

  private static final Logger LOG = LoggerFactory.getLogger(SpELExpressionPerformanceTest.class);

  private DistributedLockAspect aspect;
  private ProceedingJoinPoint joinPoint;
  private MethodSignature methodSignature;
  private Method testMethod;

  @BeforeEach
  void setUp() throws NoSuchMethodException {
    RedissonClient redissonClient = mock(RedissonClient.class);
    LocksmithProperties lockProperties =
        new LocksmithProperties(Duration.ofMinutes(10), Duration.ofSeconds(60), "lock:", false);
    aspect = new DistributedLockAspect(redissonClient, lockProperties);

    joinPoint = mock(ProceedingJoinPoint.class);
    methodSignature = mock(MethodSignature.class);
    testMethod = TestService.class.getMethod("processUser", String.class);

    when(joinPoint.getSignature()).thenReturn(methodSignature);
    when(methodSignature.getDeclaringType()).thenReturn(TestService.class);
    when(methodSignature.getName()).thenReturn("processUser");
    when(methodSignature.getMethod()).thenReturn(testMethod);
  }

  public static class TestService {
    @DistributedLock(key = "#{#userId}")
    public void processUser(String userId) {}

    @DistributedLock(key = "#{'user-' + #id + '-order-' + #orderId}")
    public void processOrder(Long id, String orderId) {}

    @DistributedLock(key = "#{#user.name + '-' + #user.email}")
    public void processUserData(User user) {}

    public record User(String name, String email, int age) {}
  }

  @Nested
  @DisplayName("Single-threaded Performance Tests")
  class SingleThreadedPerformanceTests {

    @Test
    @DisplayName("Should measure overhead of parsing simple expression repeatedly")
    void shouldMeasureSimpleExpressionParsingOverhead() {
      int iterations = 10_000;
      String expression = "#userId";

      when(joinPoint.getArgs()).thenReturn(new Object[] {"user123"});

      long startTime = System.nanoTime();
      for (int i = 0; i < iterations; i++) {
        String result = aspect.evaluateSpELExpression(expression, testMethod, joinPoint);
        assertEquals("user123", result);
      }
      long endTime = System.nanoTime();

      long totalTimeMs = (endTime - startTime) / 1_000_000;
      double avgTimePerCallNs = (endTime - startTime) / (double) iterations;

      LOG.info(
          "Simple expression (#userId) - {} iterations: total={}ms, avg={} ns/call",
          iterations,
          totalTimeMs,
          String.format("%.2f", avgTimePerCallNs));

      assertTrue(
          totalTimeMs < 5000, "10k iterations should complete in under 5 seconds (current design)");
    }

    @Test
    @DisplayName("Should measure overhead of parsing complex expression repeatedly")
    void shouldMeasureComplexExpressionParsingOverhead() throws NoSuchMethodException {
      int iterations = 10_000;
      String expression = "'user-' + #id + '-order-' + #orderId";

      Method method = TestService.class.getMethod("processOrder", Long.class, String.class);
      when(methodSignature.getMethod()).thenReturn(method);
      when(joinPoint.getArgs()).thenReturn(new Object[] {123L, "ORD456"});

      long startTime = System.nanoTime();
      for (int i = 0; i < iterations; i++) {
        String result = aspect.evaluateSpELExpression(expression, method, joinPoint);
        assertEquals("user-123-order-ORD456", result);
      }
      long endTime = System.nanoTime();

      long totalTimeMs = (endTime - startTime) / 1_000_000;
      double avgTimePerCallNs = (endTime - startTime) / (double) iterations;

      LOG.info(
          "Complex expression ('user-' + #id + '-order-' + #orderId) - {} iterations: total={}ms,"
              + " avg={} ns/call",
          iterations,
          totalTimeMs,
          String.format("%.2f", avgTimePerCallNs));

      assertTrue(
          totalTimeMs < 10000,
          "10k iterations of complex expression should complete in under 10 seconds");
    }

    @Test
    @DisplayName("Should measure overhead of parsing property access expression repeatedly")
    void shouldMeasurePropertyAccessParsingOverhead() throws NoSuchMethodException {
      int iterations = 10_000;
      String expression = "#user.name + '-' + #user.email";

      Method method = TestService.class.getMethod("processUserData", TestService.User.class);
      when(methodSignature.getMethod()).thenReturn(method);
      when(joinPoint.getArgs())
          .thenReturn(new Object[] {new TestService.User("Alice", "alice@example.com", 30)});

      long startTime = System.nanoTime();
      for (int i = 0; i < iterations; i++) {
        String result = aspect.evaluateSpELExpression(expression, method, joinPoint);
        assertEquals("Alice-alice@example.com", result);
      }
      long endTime = System.nanoTime();

      long totalTimeMs = (endTime - startTime) / 1_000_000;
      double avgTimePerCallNs = (endTime - startTime) / (double) iterations;

      LOG.info(
          "Property access expression (#user.name + '-' + #user.email) - {} iterations:"
              + " total={}ms, avg={} ns/call",
          iterations,
          totalTimeMs,
          String.format("%.2f", avgTimePerCallNs));

      assertTrue(
          totalTimeMs < 10000,
          "10k iterations of property access should complete in under 10 seconds");
    }

    @Test
    @DisplayName("Should measure parsing overhead across different expression types")
    void shouldCompareParsingOverheadAcrossDifferentExpressions() throws NoSuchMethodException {
      int iterations = 5_000;

      // Test different expression types
      List<ExpressionTest> tests =
          List.of(
              new ExpressionTest(
                  "Simple parameter", "#userId", testMethod, new Object[] {"user123"}, "user123"),
              new ExpressionTest(
                  "String concatenation",
                  "'user-' + #userId",
                  testMethod,
                  new Object[] {"user123"},
                  "user-user123"),
              new ExpressionTest(
                  "Multiple parameters",
                  "#id + '-' + #orderId",
                  TestService.class.getMethod("processOrder", Long.class, String.class),
                  new Object[] {123L, "ORD456"},
                  "123-ORD456"),
              new ExpressionTest(
                  "Property access",
                  "#user.name",
                  TestService.class.getMethod("processUserData", TestService.User.class),
                  new Object[] {new TestService.User("Bob", "bob@test.com", 25)},
                  "Bob"),
              new ExpressionTest(
                  "Complex property access",
                  "#user.name + '-' + #user.email + '-' + #user.age",
                  TestService.class.getMethod("processUserData", TestService.User.class),
                  new Object[] {new TestService.User("Charlie", "charlie@test.com", 35)},
                  "Charlie-charlie@test.com-35"));

      LOG.info(
          "Comparing parsing overhead for different expression types ({} iterations each):",
          iterations);
      LOG.info("─".repeat(80));

      for (ExpressionTest test : tests) {
        when(methodSignature.getMethod()).thenReturn(test.method);
        when(joinPoint.getArgs()).thenReturn(test.args);

        long startTime = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
          String result = aspect.evaluateSpELExpression(test.expression, test.method, joinPoint);
          assertEquals(test.expectedResult, result);
        }
        long endTime = System.nanoTime();

        long totalTimeMs = (endTime - startTime) / 1_000_000;
        double avgTimePerCallNs = (endTime - startTime) / (double) iterations;

        LOG.info(
            "{}: total={}ms, avg={} ns/call",
            String.format("%-25s", test.name),
            String.format("%5d", totalTimeMs),
            String.format("%8.2f", avgTimePerCallNs));
      }

      LOG.info("─".repeat(80));
    }
  }

  @Nested
  @DisplayName("Multi-threaded Stress Tests")
  class MultiThreadedStressTests {

    @Test
    @DisplayName("Should measure overhead under concurrent load with same expression")
    void shouldMeasureConcurrentParsingOverheadSameExpression() throws InterruptedException {
      int threadCount = 10;
      int iterationsPerThread = 5_000;
      String expression = "#userId";

      when(joinPoint.getArgs()).thenReturn(new Object[] {"user123"});

      AtomicLong totalNanoseconds = new AtomicLong(0);
      CountDownLatch startSignal = new CountDownLatch(1);
      CountDownLatch allComplete = new CountDownLatch(threadCount);

      ExecutorService executor = Executors.newFixedThreadPool(threadCount);

      long overallStart = System.nanoTime();

      for (int t = 0; t < threadCount; t++) {
        executor.submit(
            () -> {
              try {
                startSignal.await();

                long threadStart = System.nanoTime();
                for (int i = 0; i < iterationsPerThread; i++) {
                  String result = aspect.evaluateSpELExpression(expression, testMethod, joinPoint);
                  assertEquals("user123", result);
                }
                long threadEnd = System.nanoTime();

                totalNanoseconds.addAndGet(threadEnd - threadStart);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } finally {
                allComplete.countDown();
              }
            });
      }

      startSignal.countDown();
      assertTrue(allComplete.await(60, TimeUnit.SECONDS), "All threads should complete");
      long overallEnd = System.nanoTime();

      executor.shutdown();

      long overallTimeMs = (overallEnd - overallStart) / 1_000_000;
      long totalTimeMs = totalNanoseconds.get() / 1_000_000;
      int totalIterations = threadCount * iterationsPerThread;
      double avgTimePerCallNs = totalNanoseconds.get() / (double) totalIterations;
      double throughput = (totalIterations * 1000.0) / overallTimeMs;

      LOG.info(
          "Concurrent parsing ({} threads, {} iterations each):", threadCount, iterationsPerThread);
      LOG.info("  Total iterations: {}", totalIterations);
      LOG.info("  Overall time: {}ms", overallTimeMs);
      LOG.info("  Total CPU time: {}ms", totalTimeMs);
      LOG.info("  Avg time per call: {} ns", String.format("%.2f", avgTimePerCallNs));
      LOG.info("  Throughput: {} ops/sec", String.format("%.2f", throughput));

      assertTrue(overallTimeMs < 30000, "50k total iterations should complete in under 30 seconds");
    }

    @Test
    @DisplayName("Should measure overhead under concurrent load with different expressions")
    void shouldMeasureConcurrentParsingOverheadDifferentExpressions() throws InterruptedException {
      int threadCount = 5;
      int iterationsPerThread = 2_000;

      // Different expressions for different threads
      List<ExpressionTest> expressions =
          List.of(
              new ExpressionTest("Simple", "#userId", testMethod, new Object[] {"user1"}, "user1"),
              new ExpressionTest(
                  "Concatenation",
                  "'user-' + #userId",
                  testMethod,
                  new Object[] {"user2"},
                  "user-user2"),
              new ExpressionTest(
                  "Multiple params",
                  "#userId + '-test'",
                  testMethod,
                  new Object[] {"user3"},
                  "user3-test"),
              new ExpressionTest(
                  "Complex concat",
                  "'prefix-' + #userId + '-suffix'",
                  testMethod,
                  new Object[] {"user4"},
                  "prefix-user4-suffix"),
              new ExpressionTest(
                  "Upper case",
                  "#userId.toUpperCase()",
                  testMethod,
                  new Object[] {"user5"},
                  "USER5"));

      AtomicLong totalNanoseconds = new AtomicLong(0);
      CountDownLatch startSignal = new CountDownLatch(1);
      CountDownLatch allComplete = new CountDownLatch(threadCount);

      ExecutorService executor = Executors.newFixedThreadPool(threadCount);

      long overallStart = System.nanoTime();

      for (int t = 0; t < threadCount; t++) {
        final ExpressionTest test = expressions.get(t);
        executor.submit(
            () -> {
              try {
                startSignal.await();

                long threadStart = System.nanoTime();
                for (int i = 0; i < iterationsPerThread; i++) {
                  String result =
                      aspect.evaluateSpELExpression(test.expression, test.method, joinPoint);
                  // Note: using shared joinPoint mock, so we set args in the thread
                  when(joinPoint.getArgs()).thenReturn(test.args);
                  assertEquals(test.expectedResult, result);
                }
                long threadEnd = System.nanoTime();

                totalNanoseconds.addAndGet(threadEnd - threadStart);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } finally {
                allComplete.countDown();
              }
            });
      }

      startSignal.countDown();
      assertTrue(allComplete.await(60, TimeUnit.SECONDS), "All threads should complete");
      long overallEnd = System.nanoTime();

      executor.shutdown();

      long overallTimeMs = (overallEnd - overallStart) / 1_000_000;
      long totalTimeMs = totalNanoseconds.get() / 1_000_000;
      int totalIterations = threadCount * iterationsPerThread;
      double avgTimePerCallNs = totalNanoseconds.get() / (double) totalIterations;
      double throughput = (totalIterations * 1000.0) / overallTimeMs;

      LOG.info(
          "Concurrent parsing with different expressions ({} threads, {} iterations each):",
          threadCount,
          iterationsPerThread);
      LOG.info("  Total iterations: {}", totalIterations);
      LOG.info("  Overall time: {}ms", overallTimeMs);
      LOG.info("  Total CPU time: {}ms", totalTimeMs);
      LOG.info("  Avg time per call: {} ns", String.format("%.2f", avgTimePerCallNs));
      LOG.info("  Throughput: {} ops/sec", String.format("%.2f", throughput));
    }

    @Test
    @DisplayName("Should measure latency distribution under concurrent load")
    void shouldMeasureLatencyDistribution() throws InterruptedException {
      int threadCount = 8;
      int iterationsPerThread = 1_000;
      String expression = "#userId";

      when(joinPoint.getArgs()).thenReturn(new Object[] {"user123"});

      ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
      CountDownLatch startSignal = new CountDownLatch(1);
      CountDownLatch allComplete = new CountDownLatch(threadCount);

      ExecutorService executor = Executors.newFixedThreadPool(threadCount);

      for (int t = 0; t < threadCount; t++) {
        executor.submit(
            () -> {
              try {
                startSignal.await();

                for (int i = 0; i < iterationsPerThread; i++) {
                  long start = System.nanoTime();
                  String result = aspect.evaluateSpELExpression(expression, testMethod, joinPoint);
                  long latency = System.nanoTime() - start;
                  latencies.add(latency);
                  assertEquals("user123", result);
                }
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } finally {
                allComplete.countDown();
              }
            });
      }

      startSignal.countDown();
      assertTrue(allComplete.await(60, TimeUnit.SECONDS), "All threads should complete");

      executor.shutdown();

      List<Long> sortedLatencies = new ArrayList<>(latencies);
      sortedLatencies.sort(Long::compareTo);

      LongSummaryStatistics stats =
          sortedLatencies.stream().mapToLong(Long::longValue).summaryStatistics();

      long p50 = sortedLatencies.get(sortedLatencies.size() / 2);
      long p75 = sortedLatencies.get((int) (sortedLatencies.size() * 0.75));
      long p90 = sortedLatencies.get((int) (sortedLatencies.size() * 0.90));
      long p95 = sortedLatencies.get((int) (sortedLatencies.size() * 0.95));
      long p99 = sortedLatencies.get((int) (sortedLatencies.size() * 0.99));

      LOG.info("Latency distribution for SpEL expression parsing (nanoseconds):");
      LOG.info("  Count: {}", stats.getCount());
      LOG.info("  Min:  {} ns", stats.getMin());
      LOG.info("  P50:  {} ns", p50);
      LOG.info("  P75:  {} ns", p75);
      LOG.info("  P90:  {} ns", p90);
      LOG.info("  P95:  {} ns", p95);
      LOG.info("  P99:  {} ns", p99);
      LOG.info("  Max:  {} ns", stats.getMax());
      LOG.info("  Avg:  {} ns", String.format("%.2f", stats.getAverage()));

      // These are baseline numbers - after caching, we expect significant improvement
      LOG.info(
          "\nNote: These are BASELINE numbers without caching. "
              + "After implementing expression caching (Issue #28), "
              + "we expect significant improvements in all percentiles.");
    }
  }

  @Nested
  @DisplayName("Sustained Load Tests")
  class SustainedLoadTests {

    @Test
    @DisplayName("Should measure performance degradation under sustained load")
    void shouldMeasurePerformanceUnderSustainedLoad() throws InterruptedException {
      int threadCount = 5;
      int phaseDurationSeconds = 2;
      int phaseCount = 5;
      String expression = "#userId";

      when(joinPoint.getArgs()).thenReturn(new Object[] {"user123"});

      List<PhaseResult> phaseResults = new ArrayList<>();
      ExecutorService executor = Executors.newFixedThreadPool(threadCount);

      LOG.info(
          "Running sustained load test ({} phases of {}s each):", phaseCount, phaseDurationSeconds);
      LOG.info("─".repeat(80));

      for (int phase = 0; phase < phaseCount; phase++) {
        AtomicLong operationCount = new AtomicLong(0);
        AtomicLong totalLatency = new AtomicLong(0);
        CountDownLatch startSignal = new CountDownLatch(1);
        CountDownLatch phaseComplete = new CountDownLatch(threadCount);

        long phaseStart = System.nanoTime();

        for (int t = 0; t < threadCount; t++) {
          executor.submit(
              () -> {
                try {
                  startSignal.await();
                  long endTime = System.currentTimeMillis() + (phaseDurationSeconds * 1000L);

                  while (System.currentTimeMillis() < endTime) {
                    long start = System.nanoTime();
                    String result =
                        aspect.evaluateSpELExpression(expression, testMethod, joinPoint);
                    long latency = System.nanoTime() - start;

                    totalLatency.addAndGet(latency);
                    operationCount.incrementAndGet();
                    assertEquals("user123", result);
                  }
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                } finally {
                  phaseComplete.countDown();
                }
              });
        }

        startSignal.countDown();
        assertTrue(
            phaseComplete.await(phaseDurationSeconds + 10, TimeUnit.SECONDS),
            "Phase should complete");

        long phaseEnd = System.nanoTime();
        long phaseDurationMs = (phaseEnd - phaseStart) / 1_000_000;

        long ops = operationCount.get();
        double throughput = (ops * 1000.0) / phaseDurationMs;
        double avgLatencyNs = totalLatency.get() / (double) ops;

        PhaseResult result = new PhaseResult(phase + 1, ops, throughput, avgLatencyNs);
        phaseResults.add(result);

        LOG.info(
            "Phase {}: {} ops, {} ops/sec, avg latency {} ns",
            phase + 1,
            ops,
            String.format("%.2f", throughput),
            String.format("%.2f", avgLatencyNs));

        Thread.sleep(100); // Small gap between phases
      }

      executor.shutdown();

      LOG.info("─".repeat(80));

      // Analyze performance degradation
      double avgThroughput =
          phaseResults.stream().mapToDouble(r -> r.throughput).average().orElse(0);
      double minThroughput = phaseResults.stream().mapToDouble(r -> r.throughput).min().orElse(0);
      double maxThroughput = phaseResults.stream().mapToDouble(r -> r.throughput).max().orElse(0);

      double avgLatency =
          phaseResults.stream().mapToDouble(r -> r.avgLatencyNs).average().orElse(0);
      double minLatency = phaseResults.stream().mapToDouble(r -> r.avgLatencyNs).min().orElse(0);
      double maxLatency = phaseResults.stream().mapToDouble(r -> r.avgLatencyNs).max().orElse(0);

      LOG.info("Summary:");
      LOG.info(
          "  Throughput: avg={} ops/sec, min={} ops/sec, max={} ops/sec",
          String.format("%.2f", avgThroughput),
          String.format("%.2f", minThroughput),
          String.format("%.2f", maxThroughput));
      LOG.info(
          "  Latency: avg={} ns, min={} ns, max={} ns",
          String.format("%.2f", avgLatency),
          String.format("%.2f", minLatency),
          String.format("%.2f", maxLatency));

      double throughputVariation = ((maxThroughput - minThroughput) / avgThroughput) * 100;
      LOG.info("  Throughput variation: {}%", String.format("%.2f", throughputVariation));

      // Allow up to 100% variation (without caching, high variation is expected and demonstrates
      // the performance issue). After implementing caching (Issue #28), we expect this to drop
      // significantly (< 20%).
      assertTrue(
          throughputVariation < 100,
          "Throughput variation should be under 100% for baseline (current: "
              + String.format("%.2f", throughputVariation)
              + "%). High variation demonstrates the need for caching.");
    }
  }

  /** Helper record for expression test cases. */
  private record ExpressionTest(
      String name, String expression, Method method, Object[] args, String expectedResult) {}

  /** Helper record for phase results. */
  private record PhaseResult(int phase, long operations, double throughput, double avgLatencyNs) {}
}
