package in.riido.locksmith.autoconfigure;

import java.time.Duration;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * Configuration properties for the Locksmith distributed locking and semaphore mechanism.
 *
 * <p>Configure these properties in your {@code application.properties} or {@code application.yml}:
 *
 * <pre>{@code
 * # Lock configuration
 * locksmith.lock.enabled=true
 * locksmith.lock.lease-time=10m
 * locksmith.lock.wait-time=60s
 * locksmith.lock.key-prefix=lock:
 * locksmith.lock.debug=false
 *
 * # Semaphore configuration
 * locksmith.semaphore.enabled=true
 * locksmith.semaphore.lease-time=5m
 * locksmith.semaphore.wait-time=60s
 * locksmith.semaphore.key-prefix=semaphore:
 * locksmith.semaphore.debug=false
 *
 * # Rate limit configuration
 * locksmith.rate-limit.enabled=true
 * locksmith.rate-limit.wait-time=60s
 * locksmith.rate-limit.key-prefix=ratelimit:
 * locksmith.rate-limit.debug=false
 * }</pre>
 *
 * @param lock Configuration properties for distributed locks.
 * @param semaphore Configuration properties for distributed semaphores.
 * @param rateLimit Configuration properties for distributed rate limiters.
 * @author Garvit Joshi
 * @since 2.0.0
 */
@ConfigurationProperties(prefix = "locksmith")
public record LocksmithProperties(
    @NestedConfigurationProperty @NonNull LockProperties lock,
    @NestedConfigurationProperty @NonNull SemaphoreProperties semaphore,
    @NestedConfigurationProperty @NonNull RateLimitProperties rateLimit) {

  /**
   * Compact constructor that applies default values for null inputs.
   *
   * @param lock the lock properties, or null to use defaults
   * @param semaphore the semaphore properties, or null to use defaults
   * @param rateLimit the rate limit properties, or null to use defaults
   */
  public LocksmithProperties {
    if (lock == null) {
      lock = LockProperties.defaults();
    }
    if (semaphore == null) {
      semaphore = SemaphoreProperties.defaults();
    }
    if (rateLimit == null) {
      rateLimit = RateLimitProperties.defaults();
    }
  }

  /**
   * Creates a new instance with all default values.
   *
   * @return a new LocksmithProperties with default configuration
   */
  @NonNull
  public static LocksmithProperties defaults() {
    return new LocksmithProperties(
        LockProperties.defaults(), SemaphoreProperties.defaults(), RateLimitProperties.defaults());
  }

  @Override
  @NonNull
  public String toString() {
    return "LocksmithProperties[lock="
        + lock
        + ", semaphore="
        + semaphore
        + ", rateLimit="
        + rateLimit
        + "]";
  }

  /**
   * Configuration properties for distributed locks.
   *
   * @param enabled When set to false, disables the distributed lock aspect and template. Methods
   *     annotated with @DistributedLock will execute without acquiring locks. Default: true.
   * @param leaseTime The default time after which the lock is automatically released. This prevents
   *     deadlocks if a server crashes while holding a lock. Default: 10 minutes.
   * @param waitTime The default time to wait for acquiring a lock when using WAIT_AND_SKIP mode.
   *     Default: 60 seconds.
   * @param keyPrefix The prefix to use for all lock keys in Redis. Default: "lock:".
   * @param debug When enabled, logs detailed information about lock operations including key
   *     resolution, lock type, timing, and acquisition status. Default: false.
   * @param metricsEnabled When enabled, records Micrometer metrics for lock operations. Requires
   *     micrometer-core on classpath and a MeterRegistry bean. Default: false.
   */
  public record LockProperties(
      @NonNull Boolean enabled,
      @NonNull Duration leaseTime,
      @NonNull Duration waitTime,
      @NonNull String keyPrefix,
      @NonNull Boolean debug,
      @NonNull Boolean metricsEnabled) {

    /** Default enabled state for locks. */
    public static final Boolean DEFAULT_ENABLED = Boolean.TRUE;

    /** Default lease time for locks. */
    public static final Duration DEFAULT_LEASE_TIME = Duration.ofMinutes(10);

    /** Default wait time for locks. */
    public static final Duration DEFAULT_WAIT_TIME = Duration.ofSeconds(60);

    /** Default key prefix for locks. */
    public static final String DEFAULT_KEY_PREFIX = "lock:";

    /** Default debug mode. */
    public static final Boolean DEFAULT_DEBUG = Boolean.FALSE;

    /** Default metrics enabled. */
    public static final Boolean DEFAULT_METRICS_ENABLED = Boolean.FALSE;

    /**
     * Compact constructor that applies default values for null or invalid inputs.
     *
     * @param enabled the enabled flag, or null to use default
     * @param leaseTime the lease time, or null to use default
     * @param waitTime the wait time, or null to use default
     * @param keyPrefix the key prefix, or null to use default
     * @param debug the debug mode, or null to use default
     * @param metricsEnabled the metrics enabled flag, or null to use default
     */
    public LockProperties {
      if (enabled == null) {
        enabled = DEFAULT_ENABLED;
      }
      if (leaseTime == null || leaseTime.isNegative() || leaseTime.isZero()) {
        leaseTime = DEFAULT_LEASE_TIME;
      }
      if (waitTime == null || waitTime.isNegative()) {
        waitTime = DEFAULT_WAIT_TIME;
      }
      if (keyPrefix == null || keyPrefix.isBlank()) {
        keyPrefix = DEFAULT_KEY_PREFIX;
      }
      if (debug == null) {
        debug = DEFAULT_DEBUG;
      }
      if (metricsEnabled == null) {
        metricsEnabled = DEFAULT_METRICS_ENABLED;
      }
    }

    /**
     * Creates a new instance with all default values.
     *
     * @return a new LockProperties with default configuration
     */
    @NonNull
    public static LockProperties defaults() {
      return new LockProperties(
          DEFAULT_ENABLED,
          DEFAULT_LEASE_TIME,
          DEFAULT_WAIT_TIME,
          DEFAULT_KEY_PREFIX,
          DEFAULT_DEBUG,
          DEFAULT_METRICS_ENABLED);
    }

    @Override
    @NonNull
    public String toString() {
      return "LockProperties[enabled="
          + enabled
          + ", leaseTime="
          + leaseTime
          + ", waitTime="
          + waitTime
          + ", keyPrefix='"
          + keyPrefix
          + "', debug="
          + debug
          + ", metricsEnabled="
          + metricsEnabled
          + "]";
    }
  }

  /**
   * Configuration properties for distributed semaphores.
   *
   * @param enabled When set to false, disables the distributed semaphore aspect and template.
   *     Methods annotated with @DistributedSemaphore will execute without acquiring permits.
   *     Default: true.
   * @param leaseTime The default time after which the semaphore permit is automatically released.
   *     This prevents permit leaks if a server crashes while holding a permit. Default: 5 minutes.
   * @param waitTime The default time to wait for acquiring a permit when using WAIT_AND_SKIP mode.
   *     Default: 60 seconds.
   * @param keyPrefix The prefix to use for all semaphore keys in Redis. Default: "semaphore:".
   * @param debug When enabled, logs detailed information about semaphore operations including key
   *     resolution, permit acquisition, timing, and status. Default: false.
   * @param metricsEnabled When enabled, records Micrometer metrics for semaphore operations.
   *     Requires micrometer-core on classpath and a MeterRegistry bean. Default: false.
   */
  public record SemaphoreProperties(
      @NonNull Boolean enabled,
      @NonNull Duration leaseTime,
      @NonNull Duration waitTime,
      @NonNull String keyPrefix,
      @NonNull Boolean debug,
      @NonNull Boolean metricsEnabled) {

    /** Default enabled state for semaphores. */
    public static final Boolean DEFAULT_ENABLED = Boolean.TRUE;

    /** Default lease time for semaphores. */
    public static final Duration DEFAULT_LEASE_TIME = Duration.ofMinutes(5);

    /** Default wait time for semaphores. */
    public static final Duration DEFAULT_WAIT_TIME = Duration.ofSeconds(60);

    /** Default key prefix for semaphores. */
    public static final String DEFAULT_KEY_PREFIX = "semaphore:";

    /** Default debug mode. */
    public static final Boolean DEFAULT_DEBUG = Boolean.FALSE;

    /** Default metrics enabled. */
    public static final Boolean DEFAULT_METRICS_ENABLED = Boolean.FALSE;

    /**
     * Compact constructor that applies default values for null or invalid inputs.
     *
     * @param enabled the enabled flag, or null to use default
     * @param leaseTime the lease time, or null to use default
     * @param waitTime the wait time, or null to use default
     * @param keyPrefix the key prefix, or null to use default
     * @param debug the debug mode, or null to use default
     * @param metricsEnabled the metrics enabled flag, or null to use default
     */
    public SemaphoreProperties {
      if (enabled == null) {
        enabled = DEFAULT_ENABLED;
      }
      if (leaseTime == null || leaseTime.isNegative() || leaseTime.isZero()) {
        leaseTime = DEFAULT_LEASE_TIME;
      }
      if (waitTime == null || waitTime.isNegative()) {
        waitTime = DEFAULT_WAIT_TIME;
      }
      if (keyPrefix == null || keyPrefix.isBlank()) {
        keyPrefix = DEFAULT_KEY_PREFIX;
      }
      if (debug == null) {
        debug = DEFAULT_DEBUG;
      }
      if (metricsEnabled == null) {
        metricsEnabled = DEFAULT_METRICS_ENABLED;
      }
    }

    /**
     * Creates a new instance with all default values.
     *
     * @return a new SemaphoreProperties with default configuration
     */
    @NonNull
    public static SemaphoreProperties defaults() {
      return new SemaphoreProperties(
          DEFAULT_ENABLED,
          DEFAULT_LEASE_TIME,
          DEFAULT_WAIT_TIME,
          DEFAULT_KEY_PREFIX,
          DEFAULT_DEBUG,
          DEFAULT_METRICS_ENABLED);
    }

    @Override
    @NonNull
    public String toString() {
      return "SemaphoreProperties[enabled="
          + enabled
          + ", leaseTime="
          + leaseTime
          + ", waitTime="
          + waitTime
          + ", keyPrefix='"
          + keyPrefix
          + "', debug="
          + debug
          + ", metricsEnabled="
          + metricsEnabled
          + "]";
    }
  }

  /**
   * Configuration properties for distributed rate limiters.
   *
   * @param enabled When set to false, disables the rate limit aspect and template. Methods
   *     annotated with @RateLimit will execute without rate limiting. Default: true.
   * @param waitTime The default time to wait for acquiring a permit when using WAIT_AND_SKIP mode.
   *     Default: 60 seconds.
   * @param keyPrefix The prefix to use for all rate limiter keys in Redis. Default: "ratelimit:".
   * @param debug When enabled, logs detailed information about rate limit operations including key
   *     resolution, permit acquisition, timing, and status. Default: false.
   * @param metricsEnabled When enabled, records Micrometer metrics for rate limit operations.
   *     Requires micrometer-core on classpath and a MeterRegistry bean. Default: false.
   * @since 3.0.0
   */
  public record RateLimitProperties(
      @NonNull Boolean enabled,
      @NonNull Duration waitTime,
      @NonNull String keyPrefix,
      @NonNull Boolean debug,
      @NonNull Boolean metricsEnabled) {

    /** Default enabled state for rate limiters. */
    public static final Boolean DEFAULT_ENABLED = Boolean.TRUE;

    /** Default wait time for rate limiters. */
    public static final Duration DEFAULT_WAIT_TIME = Duration.ofSeconds(60);

    /** Default key prefix for rate limiters. */
    public static final String DEFAULT_KEY_PREFIX = "ratelimit:";

    /** Default debug mode. */
    public static final Boolean DEFAULT_DEBUG = Boolean.FALSE;

    /** Default metrics enabled. */
    public static final Boolean DEFAULT_METRICS_ENABLED = Boolean.FALSE;

    /**
     * Compact constructor that applies default values for null or invalid inputs.
     *
     * @param enabled the enabled flag, or null to use default
     * @param waitTime the wait time, or null to use default
     * @param keyPrefix the key prefix, or null to use default
     * @param debug the debug mode, or null to use default
     * @param metricsEnabled the metrics enabled flag, or null to use default
     */
    public RateLimitProperties {
      if (enabled == null) {
        enabled = DEFAULT_ENABLED;
      }
      if (waitTime == null || waitTime.isNegative()) {
        waitTime = DEFAULT_WAIT_TIME;
      }
      if (keyPrefix == null || keyPrefix.isBlank()) {
        keyPrefix = DEFAULT_KEY_PREFIX;
      }
      if (debug == null) {
        debug = DEFAULT_DEBUG;
      }
      if (metricsEnabled == null) {
        metricsEnabled = DEFAULT_METRICS_ENABLED;
      }
    }

    /**
     * Creates a new instance with all default values.
     *
     * @return a new RateLimitProperties with default configuration
     */
    @NonNull
    public static RateLimitProperties defaults() {
      return new RateLimitProperties(
          DEFAULT_ENABLED,
          DEFAULT_WAIT_TIME,
          DEFAULT_KEY_PREFIX,
          DEFAULT_DEBUG,
          DEFAULT_METRICS_ENABLED);
    }

    @Override
    @NonNull
    public String toString() {
      return "RateLimitProperties[enabled="
          + enabled
          + ", waitTime="
          + waitTime
          + ", keyPrefix='"
          + keyPrefix
          + "', debug="
          + debug
          + ", metricsEnabled="
          + metricsEnabled
          + "]";
    }
  }
}
