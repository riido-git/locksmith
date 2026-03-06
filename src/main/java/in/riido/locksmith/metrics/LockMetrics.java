package in.riido.locksmith.metrics;

import in.riido.locksmith.AcquisitionMode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.NonNull;

/**
 * Micrometer metrics for distributed lock operations.
 *
 * <p>Provides the following metrics:
 *
 * <ul>
 *   <li>{@code locksmith.lock.acquired} - Counter for successful lock acquisitions
 *   <li>{@code locksmith.lock.skipped} - Counter for skipped lock acquisitions (tagged by reason)
 *   <li>{@code locksmith.lock.lease.expired} - Counter for lease expirations detected
 *   <li>{@code locksmith.lock.acquisition.time} - Timer for lock acquisition duration
 *   <li>{@code locksmith.lock.held.time} - Timer for duration the lock was held
 *   <li>{@code locksmith.lock.autorenew.active} - Gauge for currently active auto-renewed locks
 * </ul>
 *
 * @author Garvit Joshi
 * @since 2.1.0
 */
public class LockMetrics {

  private static final String PREFIX = "locksmith.lock.";

  private final Counter acquired;
  private final Counter skippedTimeout;
  private final Counter skippedImmediate;
  private final Counter leaseExpired;
  private final Timer acquisitionTime;
  private final Timer heldTime;
  private final AtomicInteger autoRenewActive;

  /**
   * Creates a new LockMetrics instance and registers all metrics with the given registry.
   *
   * @param registry the Micrometer registry to register metrics with
   */
  public LockMetrics(@NonNull MeterRegistry registry) {
    this.acquired =
        Counter.builder(PREFIX + "acquired")
            .description("Number of successful lock acquisitions")
            .register(registry);

    this.skippedTimeout =
        Counter.builder(PREFIX + "skipped")
            .tag("reason", AcquisitionMode.WAIT_AND_SKIP.metricsReason())
            .description("Number of lock acquisitions skipped due to timeout")
            .register(registry);

    this.skippedImmediate =
        Counter.builder(PREFIX + "skipped")
            .tag("reason", AcquisitionMode.SKIP_IMMEDIATELY.metricsReason())
            .description("Number of lock acquisitions skipped immediately (no wait)")
            .register(registry);

    this.leaseExpired =
        Counter.builder(PREFIX + "lease.expired")
            .description("Number of lease expirations detected")
            .register(registry);

    this.acquisitionTime =
        Timer.builder(PREFIX + "acquisition.time")
            .description("Time taken to acquire the lock")
            .register(registry);

    this.heldTime =
        Timer.builder(PREFIX + "held.time")
            .description("Duration the lock was held")
            .register(registry);

    this.autoRenewActive = new AtomicInteger(0);
    registry.gauge(PREFIX + "autorenew.active", autoRenewActive);
  }

  /** Records a successful lock acquisition. */
  public void recordAcquired() {
    acquired.increment();
  }

  /**
   * Records a skipped lock acquisition.
   *
   * @param reason the reason for skipping: "timeout" for WAIT_AND_SKIP mode, "immediate" for
   *     SKIP_IMMEDIATELY mode
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
   * Records the time taken to acquire a lock.
   *
   * @param millis the acquisition time in milliseconds
   */
  public void recordAcquisitionTime(long millis) {
    acquisitionTime.record(millis, TimeUnit.MILLISECONDS);
  }

  /**
   * Records the duration a lock was held.
   *
   * @param millis the held time in milliseconds
   */
  public void recordHeldTime(long millis) {
    heldTime.record(millis, TimeUnit.MILLISECONDS);
  }

  /** Increments the count of active auto-renewed locks. */
  public void incrementAutoRenewActive() {
    autoRenewActive.incrementAndGet();
  }

  /** Decrements the count of active auto-renewed locks. */
  public void decrementAutoRenewActive() {
    autoRenewActive.decrementAndGet();
  }
}
