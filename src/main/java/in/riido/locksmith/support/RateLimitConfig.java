package in.riido.locksmith.support;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.redisson.api.RateType;

/**
 * Configuration record for rate limiter settings. Used for tracking and validating rate limiter
 * configurations both within a JVM and across Redis metadata storage.
 *
 * @param permits the number of permits per interval
 * @param intervalMs the interval in milliseconds
 * @param rateType the rate type (OVERALL or PER_CLIENT)
 * @author Garvit Joshi
 * @since 3.0.0
 */
public record RateLimitConfig(long permits, long intervalMs, @NonNull RateType rateType)
    implements Serializable {
  @Serial private static final long serialVersionUID = 1L;

  /** Validates that {@code rateType} is not null. */
  public RateLimitConfig {
    Objects.requireNonNull(rateType, "rateType must not be null");
  }

  @Override
  public String toString() {
    return "RateLimitConfig[permits="
        + permits
        + ", intervalMs="
        + intervalMs
        + ", rateType="
        + rateType
        + "]";
  }
}
