package in.riido.locksmith.metrics;

import in.riido.locksmith.AcquisitionMode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;

/**
 * Micrometer metrics for distributed semaphore operations.
 *
 * <p>Provides the following metrics:
 *
 * <ul>
 *   <li>{@code locksmith.semaphore.acquired} - Counter for successful permit acquisitions
 *   <li>{@code locksmith.semaphore.skipped} - Counter for skipped acquisitions (tagged by reason)
 *   <li>{@code locksmith.semaphore.lease.expired} - Counter for lease expirations detected
 *   <li>{@code locksmith.semaphore.acquisition.time} - Timer for permit acquisition duration
 *   <li>{@code locksmith.semaphore.held.time} - Timer for duration the permit was held
 * </ul>
 *
 * @author Garvit Joshi
 * @since 2.1.0
 */
public class SemaphoreMetrics {

  private static final String PREFIX = "locksmith.semaphore.";

  private final Counter acquired;
  private final Counter skippedTimeout;
  private final Counter skippedImmediate;
  private final Counter leaseExpired;
  private final Timer acquisitionTime;
  private final Timer heldTime;

  /**
   * Creates a new SemaphoreMetrics instance and registers all metrics with the given registry.
   *
   * @param registry the Micrometer registry to register metrics with
   */
  public SemaphoreMetrics(@NonNull MeterRegistry registry) {
    this.acquired =
        Counter.builder(PREFIX + "acquired")
            .description("Number of successful permit acquisitions")
            .register(registry);

    this.skippedTimeout =
        Counter.builder(PREFIX + "skipped")
            .tag("reason", AcquisitionMode.WAIT_AND_SKIP.metricsReason())
            .description("Number of permit acquisitions skipped due to timeout")
            .register(registry);

    this.skippedImmediate =
        Counter.builder(PREFIX + "skipped")
            .tag("reason", AcquisitionMode.SKIP_IMMEDIATELY.metricsReason())
            .description("Number of permit acquisitions skipped immediately (no wait)")
            .register(registry);

    this.leaseExpired =
        Counter.builder(PREFIX + "lease.expired")
            .description("Number of lease expirations detected")
            .register(registry);

    this.acquisitionTime =
        Timer.builder(PREFIX + "acquisition.time")
            .description("Time taken to acquire the permit")
            .register(registry);

    this.heldTime =
        Timer.builder(PREFIX + "held.time")
            .description("Duration the permit was held")
            .register(registry);
  }

  /** Records a successful permit acquisition. */
  public void recordAcquired() {
    acquired.increment();
  }

  /**
   * Records a skipped permit acquisition.
   *
   * @param reason the acquisition mode indicating the reason for skipping
   */
  public void recordSkipped(@NonNull AcquisitionMode reason) {
    if (AcquisitionMode.WAIT_AND_SKIP.equals(reason)) {
      skippedTimeout.increment();
    } else {
      skippedImmediate.increment();
    }
  }

  /** Records a lease expiration event. */
  public void recordLeaseExpired() {
    leaseExpired.increment();
  }

  /**
   * Records the time taken to acquire a permit.
   *
   * @param millis the acquisition time in milliseconds
   */
  public void recordAcquisitionTime(long millis) {
    acquisitionTime.record(millis, TimeUnit.MILLISECONDS);
  }

  /**
   * Records the duration a permit was held.
   *
   * @param millis the held time in milliseconds
   */
  public void recordHeldTime(long millis) {
    heldTime.record(millis, TimeUnit.MILLISECONDS);
  }
}
