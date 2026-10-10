package in.riido.locksmith.autoconfigure;

import in.riido.locksmith.LocksmithConfigurationException;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration properties under the {@code locksmith} prefix. Null values are replaced by the
 * defaults, so every accessor returns a value once the record is constructed. The defaults are also
 * declared with {@link DefaultValue}, so the generated configuration metadata shows them.
 *
 * @param enabled whether Locksmith registers anything; defaults to true
 * @param keyPrefix prefix of every Redis key; null or blank falls back to "locksmith:"
 * @param semaphore semaphore settings
 */
@ConfigurationProperties(prefix = "locksmith")
public record LocksmithProperties(
    @DefaultValue("true") @Nullable Boolean enabled,
    @DefaultValue(DEFAULT_KEY_PREFIX) @Nullable String keyPrefix,
    @Nullable Semaphore semaphore) {

  private static final String DEFAULT_KEY_PREFIX = "locksmith:";

  /**
   * Fills defaults for null or blank values.
   *
   * @param enabled whether Locksmith registers anything; null means true
   * @param keyPrefix prefix of every Redis key; null or blank means "locksmith:"
   * @param semaphore semaphore settings; null means the defaults
   */
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
  public record Semaphore(@DefaultValue("5m") @Nullable Duration leaseTime) {

    private static final Duration DEFAULT_LEASE_TIME = Duration.ofMinutes(5);

    /**
     * Fills the default lease time and checks that it is at least one millisecond.
     *
     * @param leaseTime default lease of a permit; null means five minutes
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
