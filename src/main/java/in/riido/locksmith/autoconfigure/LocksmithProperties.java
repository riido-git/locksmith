package in.riido.locksmith.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the Locksmith distributed locking mechanism.
 *
 * <p>Configure these properties in your {@code application.properties} or {@code application.yml}:
 *
 * <pre>{@code
 * locksmith.lease-time-minutes=10
 * locksmith.wait-time-seconds=60
 * locksmith.key-prefix=lock:
 * }</pre>
 *
 * @param leaseTimeMinutes The default time in minutes after which the lock is automatically
 *     released. This prevents deadlocks if a server crashes while holding a lock. Default: 10
 *     minutes.
 * @param waitTimeSeconds The default time in seconds to wait for acquiring a lock when using
 *     WAIT_AND_SKIP mode. Default: 60 seconds.
 * @param keyPrefix The prefix to use for all lock keys in Redis. Default: "lock:".
 * @author Garvit Joshi
 * @since 1.0.0
 */
@ConfigurationProperties(prefix = "locksmith")
public record LocksmithProperties(Long leaseTimeMinutes, Long waitTimeSeconds, String keyPrefix) {

  /** Default lease time in minutes. */
  public static final long DEFAULT_LEASE_TIME_MINUTES = 10L;

  /** Default wait time in seconds. */
  public static final long DEFAULT_WAIT_TIME_SECONDS = 60L;

  /** Default key prefix. */
  public static final String DEFAULT_KEY_PREFIX = "lock:";

  /** Compact constructor that applies default values for null or invalid inputs. */
  public LocksmithProperties {
    if (leaseTimeMinutes == null || leaseTimeMinutes <= 0) {
      leaseTimeMinutes = DEFAULT_LEASE_TIME_MINUTES;
    }
    if (waitTimeSeconds == null || waitTimeSeconds < 0) {
      waitTimeSeconds = DEFAULT_WAIT_TIME_SECONDS;
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
