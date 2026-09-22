package in.riido.locksmith.autoconfigure;

import in.riido.locksmith.LocksmithConfigurationException;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties under the {@code locksmith} prefix. Null values are replaced by the
 * defaults, so every accessor returns a value once the record is constructed.
 *
 * @param enabled whether Locksmith registers anything; defaults to {@code true}
 * @param keyPrefix prefix of every Redis key; null or blank falls back to {@code "locksmith:"}
 * @param semaphore semaphore settings; defaults to {@link Semaphore} with its defaults
 */
@ConfigurationProperties(prefix = "locksmith")
public record LocksmithProperties(
    @Nullable Boolean enabled, @Nullable String keyPrefix, @Nullable Semaphore semaphore) {

  private static final String DEFAULT_KEY_PREFIX = "locksmith:";

  /** Fills defaults for null or blank values. */
  public LocksmithProperties {
    if (enabled == null) {
      enabled = Boolean.TRUE;
    }
    if (keyPrefix == null || keyPrefix.isBlank()) {
      keyPrefix = DEFAULT_KEY_PREFIX;
    }
    if (semaphore == null) {
      semaphore = new Semaphore(null);
    }
  }

  /**
   * Semaphore settings under {@code locksmith.semaphore}.
   *
   * @param leaseTime default lease of a permit; defaults to five minutes and must be at least one
   *     millisecond
   */
  public record Semaphore(@Nullable Duration leaseTime) {

    private static final Duration DEFAULT_LEASE_TIME = Duration.ofMinutes(5);

    /**
     * Fills the default lease time and checks that it is at least one millisecond.
     *
     * @throws LocksmithConfigurationException if the lease time is shorter than one millisecond,
     *     including zero and negative values
     */
    public Semaphore {
      if (leaseTime == null) {
        leaseTime = DEFAULT_LEASE_TIME;
      }
      if (leaseTime.toMillis() <= 0) {
        throw new LocksmithConfigurationException(
            "locksmith.semaphore.lease-time must be at least one millisecond, got " + leaseTime);
      }
    }
  }
}
