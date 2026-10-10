package in.riido.locksmith.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.NonNull;

/**
 * Records {@link LocksmithMetrics} as two Micrometer timers, {@value #ACQUIRE_TIMER} and {@value
 * #HELD_TIMER}. Each timer is registered on first use of its tag combination and cached.
 */
public final class MicrometerLocksmithMetrics implements LocksmithMetrics {

  /** Timer of time spent waiting, one record per acquire attempt. */
  public static final String ACQUIRE_TIMER = "locksmith.acquire";

  /** Timer of time from acquire to release. */
  public static final String HELD_TIMER = "locksmith.held";

  /** Tag carrying {@link Primitive#tagValue()}. */
  public static final String PRIMITIVE_TAG = "primitive";

  /** Tag carrying {@link Outcome#tagValue()}. */
  public static final String OUTCOME_TAG = "outcome";

  private final @NonNull MeterRegistry registry;
  private final Map<AcquireTags, Timer> acquireTimers = new ConcurrentHashMap<>();
  private final Map<Primitive, Timer> heldTimers = new ConcurrentHashMap<>();

  /**
   * Creates the metrics on a registry.
   *
   * @param registry the registry the timers are registered on
   */
  public MicrometerLocksmithMetrics(@NonNull MeterRegistry registry) {
    this.registry = registry;
  }

  /** {@inheritDoc} */
  @Override
  public void recordAcquire(
      @NonNull Primitive primitive, @NonNull Outcome outcome, @NonNull Duration waited) {
    acquireTimers
        .computeIfAbsent(new AcquireTags(primitive, outcome), this::acquireTimer)
        .record(waited);
  }

  /** {@inheritDoc} */
  @Override
  public void recordHeld(@NonNull Primitive primitive, @NonNull Duration held) {
    heldTimers.computeIfAbsent(primitive, this::heldTimer).record(held);
  }

  private @NonNull Timer acquireTimer(@NonNull AcquireTags tags) {
    return Timer.builder(ACQUIRE_TIMER)
        .tag(PRIMITIVE_TAG, tags.primitive().tagValue())
        .tag(OUTCOME_TAG, tags.outcome().tagValue())
        .register(registry);
  }

  private @NonNull Timer heldTimer(@NonNull Primitive primitive) {
    return Timer.builder(HELD_TIMER).tag(PRIMITIVE_TAG, primitive.tagValue()).register(registry);
  }

  private record AcquireTags(@NonNull Primitive primitive, @NonNull Outcome outcome) {}
}
