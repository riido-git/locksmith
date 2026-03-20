package in.riido.locksmith.support;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.NonNull;
import org.redisson.api.RBucket;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages distributed rate limiter initialization and configuration consistency validation.
 *
 * <p>This class encapsulates the logic for:
 *
 * <ul>
 *   <li>Initializing rate limiters in Redis with metadata tracking for cross-deployment mismatch
 *       detection
 *   <li>Validating that the same rate limiter key is not used with inconsistent configurations
 *       within a JVM
 * </ul>
 *
 * <p>Used by both {@code RateLimitAspect} and {@code LocksmithRateLimitTemplate} to avoid
 * duplicating initialization logic. Each consumer creates its own instance, maintaining independent
 * JVM-level caches.
 *
 * <p><b>Race Condition Note:</b> There is a small race window between {@code trySetRate} and {@code
 * metaBucket.set} where another instance could read null from the metadata bucket. This is
 * acceptable because:
 *
 * <ul>
 *   <li>Redisson's {@code trySetRate} is itself atomic - only one instance will successfully create
 *       the rate limiter
 *   <li>The metadata is only used for logging warnings about configuration mismatches
 *   <li>Worst case: duplicate "created rate limiter" log messages on first initialization
 *   <li>The rate limiter's actual configuration in Redis is always correct
 * </ul>
 *
 * @author Garvit Joshi
 * @since 3.0.4
 */
public class RateLimitInitializer {

  private static final Logger LOG = LoggerFactory.getLogger(RateLimitInitializer.class);
  private static final String META_SUFFIX = ":rate-meta";

  /** Cache to track rate limiter configurations per key within this JVM. */
  private final Map<String, RateLimitConfig> initializedConfigs = new ConcurrentHashMap<>();

  private final RedissonClient redissonClient;

  /**
   * Creates a new {@code RateLimitInitializer} with the given Redisson client.
   *
   * @param redissonClient the Redisson client used for Redis operations
   */
  public RateLimitInitializer(@NonNull RedissonClient redissonClient) {
    this.redissonClient = redissonClient;
  }

  /**
   * Validates that the same rate limiter key is not used with different configurations within this
   * JVM. If a mismatch is detected, a warning is logged but the existing configuration is
   * preserved.
   *
   * @param rateLimitKey the full rate limiter key including prefix
   * @param config the new configuration to validate against existing
   */
  public void validateConfigConsistency(
      @NonNull String rateLimitKey, @NonNull RateLimitConfig config) {
    RateLimitConfig existingConfig = initializedConfigs.get(rateLimitKey);
    if (existingConfig != null && !existingConfig.equals(config)) {
      LOG.warn(
          "Rate limiter [{}] is configured with different settings in this JVM: "
              + "existing={}, new={}. Using existing configuration.",
          rateLimitKey,
          existingConfig,
          config);
    }
  }

  /**
   * Ensures the rate limiter is initialized in Redis with the configured rate. Uses metadata
   * storage to detect and warn about configuration mismatches across deployments.
   *
   * @param rateLimitKey the full rate limiter key including prefix
   * @param permits the number of permits per interval
   * @param interval the interval duration
   * @param rateType the rate type (OVERALL or PER_CLIENT)
   */
  public void ensureInitialized(
      @NonNull String rateLimitKey,
      long permits,
      @NonNull Duration interval,
      @NonNull RateType rateType) {

    RateLimitConfig newConfig = new RateLimitConfig(permits, interval.toMillis(), rateType);
    RateLimitConfig existingConfig = initializedConfigs.get(rateLimitKey);

    if (existingConfig != null) {
      return; // Already initialized by this JVM
    }

    String metaKey = rateLimitKey + META_SUFFIX;
    RBucket<RateLimitConfig> metaBucket = redissonClient.getBucket(metaKey);
    RRateLimiter rateLimiter = redissonClient.getRateLimiter(rateLimitKey);

    RateLimitConfig existingRedisConfig = metaBucket.get();

    if (existingRedisConfig == null) {
      boolean created = rateLimiter.trySetRate(rateType, permits, interval);

      if (created) {
        metaBucket.set(newConfig);
        LOG.info(
            "Created rate limiter [{}] with permits={}, interval={}, type={}",
            rateLimitKey,
            permits,
            interval,
            rateType);
      } else {
        // Race condition: another instance created it between our check and set
        existingRedisConfig = metaBucket.get();
        if (existingRedisConfig != null && !existingRedisConfig.equals(newConfig)) {
          LOG.warn(
              "Rate limiter [{}] was created by another instance with different settings: "
                  + "existing={}, this={}. Using existing configuration. "
                  + "To change: delete Redis keys '{}' and '{}', then redeploy all instances.",
              rateLimitKey,
              existingRedisConfig,
              newConfig,
              rateLimitKey,
              metaKey);
        }
      }
    } else if (!existingRedisConfig.equals(newConfig)) {
      LOG.warn(
          "Rate limiter [{}] exists with different settings: existing={}, this={}. "
              + "Using existing configuration. To change: delete Redis keys '{}' and '{}', "
              + "then redeploy all instances.",
          rateLimitKey,
          existingRedisConfig,
          newConfig,
          rateLimitKey,
          metaKey);
    }

    initializedConfigs.put(
        rateLimitKey, existingRedisConfig != null ? existingRedisConfig : newConfig);
  }
}
