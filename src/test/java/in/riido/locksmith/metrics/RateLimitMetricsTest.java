package in.riido.locksmith.metrics;

import static org.junit.jupiter.api.Assertions.*;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("RateLimitMetrics Tests")
class RateLimitMetricsTest {

  private MeterRegistry meterRegistry;
  private RateLimitMetrics metrics;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    metrics = new RateLimitMetrics(meterRegistry);
  }

  @Nested
  @DisplayName("Acquired Counter Tests")
  class AcquiredCounterTests {

    @Test
    @DisplayName("Should record acquired counter")
    void shouldRecordAcquiredCounter() {
      metrics.recordAcquired();

      Counter counter = meterRegistry.find("locksmith.ratelimit.acquired").counter();
      assertNotNull(counter);
      assertEquals(1, counter.count());
    }

    @Test
    @DisplayName("Should record multiple acquisitions")
    void shouldRecordMultipleAcquisitions() {
      metrics.recordAcquired();
      metrics.recordAcquired();
      metrics.recordAcquired();

      Counter counter = meterRegistry.find("locksmith.ratelimit.acquired").counter();
      assertEquals(3, counter.count());
    }
  }

  @Nested
  @DisplayName("Exceeded Counter Tests")
  class ExceededCounterTests {

    @Test
    @DisplayName("Should record exceeded counter with immediate reason")
    void shouldRecordExceededCounterWithImmediateReason() {
      metrics.recordExceeded("immediate");

      Counter counter =
          meterRegistry.find("locksmith.ratelimit.exceeded").tag("reason", "immediate").counter();
      assertNotNull(counter);
      assertEquals(1, counter.count());
    }

    @Test
    @DisplayName("Should record exceeded counter with timeout reason")
    void shouldRecordExceededCounterWithTimeoutReason() {
      metrics.recordExceeded("timeout");

      Counter counter =
          meterRegistry.find("locksmith.ratelimit.exceeded").tag("reason", "timeout").counter();
      assertNotNull(counter);
      assertEquals(1, counter.count());
    }

    @Test
    @DisplayName("Should use immediate counter for non-timeout reason")
    void shouldUseImmediateCounterForNonTimeoutReason() {
      // Any reason other than "timeout" should use the immediate counter
      metrics.recordExceeded("interrupted");

      Counter counter =
          meterRegistry.find("locksmith.ratelimit.exceeded").tag("reason", "immediate").counter();
      assertNotNull(counter);
      assertEquals(1, counter.count());
    }

    @Test
    @DisplayName("Should track different reasons separately")
    void shouldTrackDifferentReasonsSeparately() {
      metrics.recordExceeded("immediate");
      metrics.recordExceeded("immediate");
      metrics.recordExceeded("timeout");

      Counter immediateCounter =
          meterRegistry.find("locksmith.ratelimit.exceeded").tag("reason", "immediate").counter();
      Counter timeoutCounter =
          meterRegistry.find("locksmith.ratelimit.exceeded").tag("reason", "timeout").counter();

      assertEquals(2, immediateCounter.count());
      assertEquals(1, timeoutCounter.count());
    }

    @Test
    @DisplayName("Should use immediate counter for unknown reason")
    void shouldUseImmediateCounterForUnknownReason() {
      metrics.recordExceeded("unknown");

      Counter counter =
          meterRegistry.find("locksmith.ratelimit.exceeded").tag("reason", "immediate").counter();
      assertNotNull(counter);
      assertEquals(1, counter.count());
    }
  }

  @Nested
  @DisplayName("Acquisition Time Timer Tests")
  class AcquisitionTimeTimerTests {

    @Test
    @DisplayName("Should record acquisition time")
    void shouldRecordAcquisitionTime() {
      metrics.recordAcquisitionTime(150L);

      Timer timer = meterRegistry.find("locksmith.ratelimit.acquisition.time").timer();
      assertNotNull(timer);
      assertEquals(1, timer.count());
      assertTrue(timer.totalTime(TimeUnit.MILLISECONDS) >= 150);
    }

    @Test
    @DisplayName("Should accumulate acquisition times")
    void shouldAccumulateAcquisitionTimes() {
      metrics.recordAcquisitionTime(100L);
      metrics.recordAcquisitionTime(200L);
      metrics.recordAcquisitionTime(300L);

      Timer timer = meterRegistry.find("locksmith.ratelimit.acquisition.time").timer();
      assertEquals(3, timer.count());
      assertTrue(timer.totalTime(TimeUnit.MILLISECONDS) >= 600);
    }
  }

  @Nested
  @DisplayName("Execution Time Timer Tests")
  class ExecutionTimeTimerTests {

    @Test
    @DisplayName("Should record execution time")
    void shouldRecordExecutionTime() {
      metrics.recordExecutionTime(250L);

      Timer timer = meterRegistry.find("locksmith.ratelimit.execution.time").timer();
      assertNotNull(timer);
      assertEquals(1, timer.count());
      assertTrue(timer.totalTime(TimeUnit.MILLISECONDS) >= 250);
    }

    @Test
    @DisplayName("Should accumulate execution times")
    void shouldAccumulateExecutionTimes() {
      metrics.recordExecutionTime(100L);
      metrics.recordExecutionTime(200L);

      Timer timer = meterRegistry.find("locksmith.ratelimit.execution.time").timer();
      assertEquals(2, timer.count());
      assertTrue(timer.totalTime(TimeUnit.MILLISECONDS) >= 300);
    }
  }

  @Nested
  @DisplayName("Edge Case Tests")
  class EdgeCaseTests {

    @Test
    @DisplayName("Should handle zero acquisition time")
    void shouldHandleZeroAcquisitionTime() {
      metrics.recordAcquisitionTime(0L);

      Timer timer = meterRegistry.find("locksmith.ratelimit.acquisition.time").timer();
      assertNotNull(timer);
      assertEquals(1, timer.count());
    }

    @Test
    @DisplayName("Should handle zero execution time")
    void shouldHandleZeroExecutionTime() {
      metrics.recordExecutionTime(0L);

      Timer timer = meterRegistry.find("locksmith.ratelimit.execution.time").timer();
      assertNotNull(timer);
      assertEquals(1, timer.count());
    }
  }
}
