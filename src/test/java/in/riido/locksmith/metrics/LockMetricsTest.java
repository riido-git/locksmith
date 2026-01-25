package in.riido.locksmith.metrics;

import static org.junit.jupiter.api.Assertions.*;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("LockMetrics Tests")
class LockMetricsTest {

  private MeterRegistry registry;
  private LockMetrics lockMetrics;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    lockMetrics = new LockMetrics(registry);
  }

  @Nested
  @DisplayName("Counter Tests")
  class CounterTests {

    @Test
    @DisplayName("Should register acquired counter")
    void shouldRegisterAcquiredCounter() {
      Counter counter = registry.find("locksmith.lock.acquired").counter();
      assertNotNull(counter);
      assertEquals(0, counter.count());
    }

    @Test
    @DisplayName("Should increment acquired counter")
    void shouldIncrementAcquiredCounter() {
      lockMetrics.recordAcquired();
      lockMetrics.recordAcquired();

      Counter counter = registry.find("locksmith.lock.acquired").counter();
      assertEquals(2, counter.count());
    }

    @Test
    @DisplayName("Should register skipped counter with timeout reason")
    void shouldRegisterSkippedCounterWithTimeoutReason() {
      Counter counter = registry.find("locksmith.lock.skipped").tag("reason", "timeout").counter();
      assertNotNull(counter);
      assertEquals(0, counter.count());
    }

    @Test
    @DisplayName("Should register skipped counter with immediate reason")
    void shouldRegisterSkippedCounterWithImmediateReason() {
      Counter counter =
          registry.find("locksmith.lock.skipped").tag("reason", "immediate").counter();
      assertNotNull(counter);
      assertEquals(0, counter.count());
    }

    @Test
    @DisplayName("Should increment skipped counter with correct reason - timeout")
    void shouldIncrementSkippedCounterWithTimeoutReason() {
      lockMetrics.recordSkipped("timeout");

      Counter counter = registry.find("locksmith.lock.skipped").tag("reason", "timeout").counter();
      assertEquals(1, counter.count());
    }

    @Test
    @DisplayName("Should increment skipped counter with correct reason - immediate")
    void shouldIncrementSkippedCounterWithImmediateReason() {
      lockMetrics.recordSkipped("immediate");

      Counter counter =
          registry.find("locksmith.lock.skipped").tag("reason", "immediate").counter();
      assertEquals(1, counter.count());
    }

    @Test
    @DisplayName("Should register lease expired counter")
    void shouldRegisterLeaseExpiredCounter() {
      Counter counter = registry.find("locksmith.lock.lease.expired").counter();
      assertNotNull(counter);
      assertEquals(0, counter.count());
    }

    @Test
    @DisplayName("Should increment lease expired counter")
    void shouldIncrementLeaseExpiredCounter() {
      lockMetrics.recordLeaseExpired();
      lockMetrics.recordLeaseExpired();
      lockMetrics.recordLeaseExpired();

      Counter counter = registry.find("locksmith.lock.lease.expired").counter();
      assertEquals(3, counter.count());
    }
  }

  @Nested
  @DisplayName("Timer Tests")
  class TimerTests {

    @Test
    @DisplayName("Should register acquisition time timer")
    void shouldRegisterAcquisitionTimeTimer() {
      Timer timer = registry.find("locksmith.lock.acquisition.time").timer();
      assertNotNull(timer);
      assertEquals(0, timer.count());
    }

    @Test
    @DisplayName("Should record acquisition time")
    void shouldRecordAcquisitionTime() {
      lockMetrics.recordAcquisitionTime(100);
      lockMetrics.recordAcquisitionTime(200);

      Timer timer = registry.find("locksmith.lock.acquisition.time").timer();
      assertEquals(2, timer.count());
      assertTrue(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS) >= 300);
    }

    @Test
    @DisplayName("Should register held time timer")
    void shouldRegisterHeldTimeTimer() {
      Timer timer = registry.find("locksmith.lock.held.time").timer();
      assertNotNull(timer);
      assertEquals(0, timer.count());
    }

    @Test
    @DisplayName("Should record held time")
    void shouldRecordHeldTime() {
      lockMetrics.recordHeldTime(500);
      lockMetrics.recordHeldTime(1000);

      Timer timer = registry.find("locksmith.lock.held.time").timer();
      assertEquals(2, timer.count());
      assertTrue(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS) >= 1500);
    }
  }

  @Nested
  @DisplayName("Gauge Tests")
  class GaugeTests {

    @Test
    @DisplayName("Should register auto renew active gauge")
    void shouldRegisterAutoRenewActiveGauge() {
      Gauge gauge = registry.find("locksmith.lock.autorenew.active").gauge();
      assertNotNull(gauge);
      assertEquals(0, gauge.value());
    }

    @Test
    @DisplayName("Should increment auto renew active gauge")
    void shouldIncrementAutoRenewActiveGauge() {
      lockMetrics.incrementAutoRenewActive();
      lockMetrics.incrementAutoRenewActive();

      Gauge gauge = registry.find("locksmith.lock.autorenew.active").gauge();
      assertEquals(2, gauge.value());
    }

    @Test
    @DisplayName("Should decrement auto renew active gauge")
    void shouldDecrementAutoRenewActiveGauge() {
      lockMetrics.incrementAutoRenewActive();
      lockMetrics.incrementAutoRenewActive();
      lockMetrics.decrementAutoRenewActive();

      Gauge gauge = registry.find("locksmith.lock.autorenew.active").gauge();
      assertEquals(1, gauge.value());
    }

    @Test
    @DisplayName("Should allow negative auto renew active gauge")
    void shouldAllowNegativeAutoRenewActiveGauge() {
      lockMetrics.decrementAutoRenewActive();

      Gauge gauge = registry.find("locksmith.lock.autorenew.active").gauge();
      assertEquals(-1, gauge.value());
    }
  }
}
