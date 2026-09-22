package in.riido.locksmith.metrics;

import java.time.Duration;
import org.jspecify.annotations.NonNull;

/** {@link LocksmithMetrics} that records nothing; used when no {@code MeterRegistry} exists. */
public final class NoOpLocksmithMetrics implements LocksmithMetrics {

  /** Creates the no-op metrics. */
  public NoOpLocksmithMetrics() {}

  /** Does nothing. */
  @Override
  public void recordAcquire(
      @NonNull Primitive primitive, @NonNull Outcome outcome, @NonNull Duration waited) {}

  /** Does nothing. */
  @Override
  public void recordHeld(@NonNull Primitive primitive, @NonNull Duration held) {}
}
