package in.riido.locksmith.metrics;

import in.riido.locksmith.AcquisitionMode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;

/**
 * Micrometer metrics for distributed rate limit operations.
 *
 * <p>Provides the following metrics:
 *
 * <ul>
 *   <li>{@code locksmith.rate.limit.acquired} - Counter for successful permit acquisitions
 *   <li>{@code locksmith.rate.limit.exceeded} - Counter for rate limit exceeded (tagged by reason)
 *   <li>{@code locksmith.rate.limit.acquisition.time} - Timer for permit acquisition duration
 *   <li>{@code locksmith.rate.limit.execution.time} - Timer for method execution time after permit
 *       acquired
 * </ul>
 *
 * @author Garvit Joshi
 * @since 3.0.0
 */
public class RateLimitMetrics {

  private static final String PREFIX = "locksmith.rate.limit.";

  private final Counter acquired;
  private final Counter exceededTimeout;
  private final Counter exceededImmediate;
  private final Timer acquisitionTime;
  private final Timer executionTime;

  /**
   * Creates a new RateLimitMetrics instance and registers all metrics with the given registry.
   *
   * @param registry the Micrometer registry to register metrics with
   */
  public RateLimitMetrics(@NonNull MeterRegistry registry) {
    this.acquired =
        Counter.builder(PREFIX + "acquired")
            .description("Number of successful rate limit permit acquisitions")
            .register(registry);

    this.exceededTimeout =
        Counter.builder(PREFIX + "exceeded")
            .tag("reason", AcquisitionMode.WAIT_AND_SKIP.metricsReason())
            .description("Number of rate limits exceeded after timeout")
            .register(registry);

    this.exceededImmediate =
        Counter.builder(PREFIX + "exceeded")
            .tag("reason", AcquisitionMode.SKIP_IMMEDIATELY.metricsReason())
            .description("Number of rate limits exceeded immediately (no wait)")
            .register(registry);

    this.acquisitionTime =
        Timer.builder(PREFIX + "acquisition.time")
            .description("Time taken to acquire the rate limit permit")
            .register(registry);

    this.executionTime =
        Timer.builder(PREFIX + "execution.time")
            .description("Method execution time after permit acquired")
            .register(registry);
  }

  /** Records a successful permit acquisition. */
  public void recordAcquired() {
    acquired.increment();
  }

  /**
   * Records a rate limit exceeded event.
   *
   * @param reason the acquisition mode indicating the reason for exceeding
   */
  public void recordExceeded(@NonNull AcquisitionMode reason) {
    if (AcquisitionMode.WAIT_AND_SKIP.equals(reason)) {
      exceededTimeout.increment();
    } else {
      exceededImmediate.increment();
    }
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
   * Records the method execution time after permit was acquired.
   *
   * @param millis the execution time in milliseconds
   */
  public void recordExecutionTime(long millis) {
    executionTime.record(millis, TimeUnit.MILLISECONDS);
  }
}
