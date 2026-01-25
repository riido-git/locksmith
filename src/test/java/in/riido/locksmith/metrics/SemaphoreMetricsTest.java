package in.riido.locksmith.metrics;

import static org.junit.jupiter.api.Assertions.*;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("SemaphoreMetrics Tests")
class SemaphoreMetricsTest {

  private MeterRegistry registry;
  private SemaphoreMetrics semaphoreMetrics;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    semaphoreMetrics = new SemaphoreMetrics(registry);
  }

  @Nested
  @DisplayName("Counter Tests")
  class CounterTests {

    @Test
    @DisplayName("Should register acquired counter")
    void shouldRegisterAcquiredCounter() {
      Counter counter = registry.find("locksmith.semaphore.acquired").counter();
      assertNotNull(counter);
      assertEquals(0, counter.count());
    }

    @Test
    @DisplayName("Should increment acquired counter")
    void shouldIncrementAcquiredCounter() {
      semaphoreMetrics.recordAcquired();
      semaphoreMetrics.recordAcquired();

      Counter counter = registry.find("locksmith.semaphore.acquired").counter();
      assertEquals(2, counter.count());
    }

    @Test
    @DisplayName("Should register skipped counter with timeout reason")
    void shouldRegisterSkippedCounterWithTimeoutReason() {
      Counter counter =
          registry.find("locksmith.semaphore.skipped").tag("reason", "timeout").counter();
      assertNotNull(counter);
      assertEquals(0, counter.count());
    }

    @Test
    @DisplayName("Should register skipped counter with immediate reason")
    void shouldRegisterSkippedCounterWithImmediateReason() {
      Counter counter =
          registry.find("locksmith.semaphore.skipped").tag("reason", "immediate").counter();
      assertNotNull(counter);
      assertEquals(0, counter.count());
    }

    @Test
    @DisplayName("Should increment skipped counter with correct reason - timeout")
    void shouldIncrementSkippedCounterWithTimeoutReason() {
      semaphoreMetrics.recordSkipped("timeout");

      Counter counter =
          registry.find("locksmith.semaphore.skipped").tag("reason", "timeout").counter();
      assertEquals(1, counter.count());
    }

    @Test
    @DisplayName("Should increment skipped counter with correct reason - immediate")
    void shouldIncrementSkippedCounterWithImmediateReason() {
      semaphoreMetrics.recordSkipped("immediate");

      Counter counter =
          registry.find("locksmith.semaphore.skipped").tag("reason", "immediate").counter();
      assertEquals(1, counter.count());
    }

    @Test
    @DisplayName("Should register lease expired counter")
    void shouldRegisterLeaseExpiredCounter() {
      Counter counter = registry.find("locksmith.semaphore.lease.expired").counter();
      assertNotNull(counter);
      assertEquals(0, counter.count());
    }

    @Test
    @DisplayName("Should increment lease expired counter")
    void shouldIncrementLeaseExpiredCounter() {
      semaphoreMetrics.recordLeaseExpired();
      semaphoreMetrics.recordLeaseExpired();
      semaphoreMetrics.recordLeaseExpired();

      Counter counter = registry.find("locksmith.semaphore.lease.expired").counter();
      assertEquals(3, counter.count());
    }
  }

  @Nested
  @DisplayName("Timer Tests")
  class TimerTests {

    @Test
    @DisplayName("Should register acquisition time timer")
    void shouldRegisterAcquisitionTimeTimer() {
      Timer timer = registry.find("locksmith.semaphore.acquisition.time").timer();
      assertNotNull(timer);
      assertEquals(0, timer.count());
    }

    @Test
    @DisplayName("Should record acquisition time")
    void shouldRecordAcquisitionTime() {
      semaphoreMetrics.recordAcquisitionTime(100);
      semaphoreMetrics.recordAcquisitionTime(200);

      Timer timer = registry.find("locksmith.semaphore.acquisition.time").timer();
      assertEquals(2, timer.count());
      assertTrue(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS) >= 300);
    }

    @Test
    @DisplayName("Should register held time timer")
    void shouldRegisterHeldTimeTimer() {
      Timer timer = registry.find("locksmith.semaphore.held.time").timer();
      assertNotNull(timer);
      assertEquals(0, timer.count());
    }

    @Test
    @DisplayName("Should record held time")
    void shouldRecordHeldTime() {
      semaphoreMetrics.recordHeldTime(500);
      semaphoreMetrics.recordHeldTime(1000);

      Timer timer = registry.find("locksmith.semaphore.held.time").timer();
      assertEquals(2, timer.count());
      assertTrue(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS) >= 1500);
    }
  }
}
