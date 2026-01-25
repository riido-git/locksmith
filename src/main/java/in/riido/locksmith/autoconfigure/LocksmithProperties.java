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
 * locksmith.lock.lease-time=10m
 * locksmith.lock.wait-time=60s
 * locksmith.lock.key-prefix=lock:
 * locksmith.lock.debug=false
 *
 * # Semaphore configuration
 * locksmith.semaphore.lease-time=5m
 * locksmith.semaphore.wait-time=60s
 * locksmith.semaphore.key-prefix=semaphore:
 * locksmith.semaphore.debug=false
 * }</pre>
 *
 * @param lock Configuration properties for distributed locks.
 * @param semaphore Configuration properties for distributed semaphores.
 * @author Garvit Joshi
 * @since 2.0.0
 */
@ConfigurationProperties(prefix = "locksmith")
public record LocksmithProperties(
    @NestedConfigurationProperty @NonNull LockProperties lock,
    @NestedConfigurationProperty @NonNull SemaphoreProperties semaphore) {

  /**
   * Compact constructor that applies default values for null inputs.
   *
   * @param lock the lock properties, or null to use defaults
   * @param semaphore the semaphore properties, or null to use defaults
   */
  public LocksmithProperties {
    if (lock == null) {
      lock = LockProperties.defaults();
    }
    if (semaphore == null) {
      semaphore = SemaphoreProperties.defaults();
    }
  }

  /**
   * Creates a new instance with all default values.
   *
   * @return a new LocksmithProperties with default configuration
   */
  @NonNull
  public static LocksmithProperties defaults() {
    return new LocksmithProperties(LockProperties.defaults(), SemaphoreProperties.defaults());
  }

  @Override
  @NonNull
  public String toString() {
    return "LocksmithProperties[lock=" + lock + ", semaphore=" + semaphore + "]";
  }

  /**
   * Configuration properties for distributed locks.
   *
   * @param leaseTime The default time after which the lock is automatically released. This prevents
   *     deadlocks if a server crashes while holding a lock. Default: 10 minutes.
   * @param waitTime The default time to wait for acquiring a lock when using WAIT_AND_SKIP mode.
   *     Default: 60 seconds.
   * @param keyPrefix The prefix to use for all lock keys in Redis. Default: "lock:".
   * @param debug When enabled, logs detailed information about lock operations including key
   *     resolution, lock type, timing, and acquisition status. Default: false.
   */
  public record LockProperties(
      @NonNull Duration leaseTime,
      @NonNull Duration waitTime,
      @NonNull String keyPrefix,
      @NonNull Boolean debug) {

    /** Default lease time for locks. */
    public static final Duration DEFAULT_LEASE_TIME = Duration.ofMinutes(10);

    /** Default wait time for locks. */
    public static final Duration DEFAULT_WAIT_TIME = Duration.ofSeconds(60);

    /** Default key prefix for locks. */
    public static final String DEFAULT_KEY_PREFIX = "lock:";

    /** Default debug mode. */
    public static final Boolean DEFAULT_DEBUG = Boolean.FALSE;

    /**
     * Compact constructor that applies default values for null or invalid inputs.
     *
     * @param leaseTime the lease time, or null to use default
     * @param waitTime the wait time, or null to use default
     * @param keyPrefix the key prefix, or null to use default
     * @param debug the debug mode, or null to use default
     */
    public LockProperties {
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
    }

    /**
     * Creates a new instance with all default values.
     *
     * @return a new LockProperties with default configuration
     */
    @NonNull
    public static LockProperties defaults() {
      return new LockProperties(
          DEFAULT_LEASE_TIME, DEFAULT_WAIT_TIME, DEFAULT_KEY_PREFIX, DEFAULT_DEBUG);
    }

    @Override
    @NonNull
    public String toString() {
      return "LockProperties[leaseTime="
          + leaseTime
          + ", waitTime="
          + waitTime
          + ", keyPrefix='"
          + keyPrefix
          + "', debug="
          + debug
          + "]";
    }
  }

  /**
   * Configuration properties for distributed semaphores.
   *
   * @param leaseTime The default time after which the semaphore permit is automatically released.
   *     This prevents permit leaks if a server crashes while holding a permit. Default: 5 minutes.
   * @param waitTime The default time to wait for acquiring a permit when using WAIT_AND_SKIP mode.
   *     Default: 60 seconds.
   * @param keyPrefix The prefix to use for all semaphore keys in Redis. Default: "semaphore:".
   * @param debug When enabled, logs detailed information about semaphore operations including key
   *     resolution, permit acquisition, timing, and status. Default: false.
   */
  public record SemaphoreProperties(
      @NonNull Duration leaseTime,
      @NonNull Duration waitTime,
      @NonNull String keyPrefix,
      @NonNull Boolean debug) {

    /** Default lease time for semaphores. */
    public static final Duration DEFAULT_LEASE_TIME = Duration.ofMinutes(5);

    /** Default wait time for semaphores. */
    public static final Duration DEFAULT_WAIT_TIME = Duration.ofSeconds(60);

    /** Default key prefix for semaphores. */
    public static final String DEFAULT_KEY_PREFIX = "semaphore:";

    /** Default debug mode. */
    public static final Boolean DEFAULT_DEBUG = Boolean.FALSE;

    /**
     * Compact constructor that applies default values for null or invalid inputs.
     *
     * @param leaseTime the lease time, or null to use default
     * @param waitTime the wait time, or null to use default
     * @param keyPrefix the key prefix, or null to use default
     * @param debug the debug mode, or null to use default
     */
    public SemaphoreProperties {
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
    }

    /**
     * Creates a new instance with all default values.
     *
     * @return a new SemaphoreProperties with default configuration
     */
    @NonNull
    public static SemaphoreProperties defaults() {
      return new SemaphoreProperties(
          DEFAULT_LEASE_TIME, DEFAULT_WAIT_TIME, DEFAULT_KEY_PREFIX, DEFAULT_DEBUG);
    }

    @Override
    @NonNull
    public String toString() {
      return "SemaphoreProperties[leaseTime="
          + leaseTime
          + ", waitTime="
          + waitTime
          + ", keyPrefix='"
          + keyPrefix
          + "', debug="
          + debug
          + "]";
    }
  }
}
