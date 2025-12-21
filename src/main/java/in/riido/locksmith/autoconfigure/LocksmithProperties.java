package in.riido.locksmith.autoconfigure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the Locksmith distributed locking mechanism.
 *
 * <p>Configure these properties in your {@code application.properties} or {@code application.yml}:
 *
 * <pre>{@code
 * locksmith.lease-time=10m
 * locksmith.wait-time=60s
 * locksmith.key-prefix=lock:
 * }</pre>
 *
 * @param leaseTime The default time after which the lock is automatically released. This prevents
 *     deadlocks if a server crashes while holding a lock. Default: 10 minutes.
 * @param waitTime The default time to wait for acquiring a lock when using WAIT_AND_SKIP mode.
 *     Default: 60 seconds.
 * @param keyPrefix The prefix to use for all lock keys in Redis. Default: "lock:".
 * @author Garvit Joshi
 * @since 1.0.0
 */
@ConfigurationProperties(prefix = "locksmith")
public record LocksmithProperties(Duration leaseTime, Duration waitTime, String keyPrefix) {

  /** Default lease time. */
  public static final Duration DEFAULT_LEASE_TIME = Duration.ofMinutes(10);

  /** Default wait time. */
  public static final Duration DEFAULT_WAIT_TIME = Duration.ofSeconds(60);

  /** Default key prefix. */
  public static final String DEFAULT_KEY_PREFIX = "lock:";

  /**
   * Compact constructor that applies default values for null or invalid inputs.
   *
   * @param leaseTime the lease time, or null to use default
   * @param waitTime the wait time, or null to use default
   * @param keyPrefix the key prefix, or null to use default
   */
  public LocksmithProperties {
    if (leaseTime == null || leaseTime.isNegative() || leaseTime.isZero()) {
      leaseTime = DEFAULT_LEASE_TIME;
    }
    if (waitTime == null || waitTime.isNegative()) {
      waitTime = DEFAULT_WAIT_TIME;
    }
    if (keyPrefix == null || keyPrefix.isBlank()) {
      keyPrefix = DEFAULT_KEY_PREFIX;
    }
  }

  /**
   * Creates a new instance with all default values.
   *
   * @return a new LocksmithProperties with default configuration
   */
  public static LocksmithProperties defaults() {
    return new LocksmithProperties(null, null, null);
  }
}
