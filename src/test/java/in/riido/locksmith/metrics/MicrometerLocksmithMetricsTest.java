package in.riido.locksmith.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import in.riido.locksmith.metrics.LocksmithMetrics.Outcome;
import in.riido.locksmith.metrics.LocksmithMetrics.Primitive;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("MicrometerLocksmithMetrics")
class MicrometerLocksmithMetricsTest {

  private SimpleMeterRegistry registry;
  private MicrometerLocksmithMetrics metrics;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    metrics = new MicrometerLocksmithMetrics(registry);
  }

  @Nested
  @DisplayName("recordAcquire")
  class RecordAcquire {

    @Test
    @DisplayName("registers locksmith.acquire tagged primitive=lock, outcome=acquired")
    void registersAcquireTimerWithTags() {
      metrics.recordAcquire(Primitive.LOCK, Outcome.ACQUIRED, Duration.ofMillis(20));

      Timer timer =
          registry
              .find("locksmith.acquire")
              .tag("primitive", "lock")
              .tag("outcome", "acquired")
              .timer();
      assertThat(timer).isNotNull();
      assertThat(timer.getId().getTags()).hasSize(2);
      assertThat(timer.count()).isEqualTo(1);
      assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(20);
    }

    @ParameterizedTest(name = "outcome {0} is tagged with its tag value")
    @EnumSource(Outcome.class)
    @DisplayName("each outcome gets its own semaphore timer tagged with the enum's tag value")
    void eachOutcomeTagged(Outcome outcome) {
      metrics.recordAcquire(Primitive.SEMAPHORE, outcome, Duration.ZERO);

      assertThat(
              registry
                  .find("locksmith.acquire")
                  .tag("primitive", "semaphore")
                  .tag("outcome", outcome.tagValue())
                  .timer())
          .isNotNull();
    }

    @Test
    @DisplayName("repeated records with the same tags reuse one timer")
    void sameTagsReuseOneTimer() {
      metrics.recordAcquire(Primitive.LOCK, Outcome.SKIPPED, Duration.ofMillis(1));
      metrics.recordAcquire(Primitive.LOCK, Outcome.SKIPPED, Duration.ofMillis(2));

      assertThat(registry.find("locksmith.acquire").timers()).hasSize(1);
      assertThat(registry.get("locksmith.acquire").timer().count()).isEqualTo(2);
    }
  }

  @Nested
  @DisplayName("recordHeld")
  class RecordHeld {

    @Test
    @DisplayName("registers locksmith.held tagged only primitive=lock")
    void registersHeldTimerWithPrimitiveTag() {
      metrics.recordHeld(Primitive.LOCK, Duration.ofMillis(50));

      Timer timer = registry.find("locksmith.held").tag("primitive", "lock").timer();
      assertThat(timer).isNotNull();
      assertThat(timer.getId().getTags()).hasSize(1);
      assertThat(timer.count()).isEqualTo(1);
      assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(50);
    }

    @Test
    @DisplayName("lock and semaphore get separate held timers")
    void separateTimersPerPrimitive() {
      metrics.recordHeld(Primitive.LOCK, Duration.ofMillis(1));
      metrics.recordHeld(Primitive.SEMAPHORE, Duration.ofMillis(1));

      assertThat(registry.find("locksmith.held").timers()).hasSize(2);
      assertThat(registry.find("locksmith.held").tag("primitive", "semaphore").timer()).isNotNull();
    }
  }
}
