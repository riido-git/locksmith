package in.riido.locksmith.support;

import in.riido.locksmith.exception.SemaphoreConfigurationException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.redisson.api.RBucket;
import org.redisson.api.RPermitExpirableSemaphore;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages distributed semaphore initialization and permits consistency validation.
 *
 * <p>This class encapsulates the logic for:
 *
 * <ul>
 *   <li>Initializing semaphores in Redis with metadata tracking for cross-deployment mismatch
 *       detection
 *   <li>Validating that the same semaphore key is not used with inconsistent permit counts within a
 *       JVM
 * </ul>
 *
 * <p>Used by both {@code DistributedSemaphoreAspect} and {@code LocksmithSemaphoreTemplate} to
 * avoid duplicating initialization logic. Each consumer creates its own instance, maintaining
 * independent JVM-level caches.
 *
 * <p><b>Race Condition Note:</b> There is a small race window between {@code trySetPermits} and
 * {@code metaBucket.set} where another instance could read null from the metadata bucket. This is
 * acceptable because:
 *
 * <ul>
 *   <li>Redisson's {@code trySetPermits} is itself atomic - only one instance will successfully
 *       create the semaphore
 *   <li>The metadata is only used for logging warnings about permit mismatches
 *   <li>Worst case: duplicate "created semaphore" log messages on first initialization
 *   <li>The semaphore's actual permit count in Redis is always correct
 * </ul>
 *
 * @author Garvit Joshi
 * @since 3.0.3
 */
public class SemaphoreInitializer {

  private static final Logger LOG = LoggerFactory.getLogger(SemaphoreInitializer.class);
  private static final String META_SUFFIX = ":meta";

  /** Cache to track which keys have been initialized in Redis by this JVM. */
  private final Map<String, Boolean> initializedKeys = new ConcurrentHashMap<>();

  /** Cache to track permits per key within this JVM for consistency validation. */
  private final Map<String, Integer> keyToPermits = new ConcurrentHashMap<>();

  private final RedissonClient redissonClient;

  /**
   * Creates a new {@code SemaphoreInitializer} with the given Redisson client.
   *
   * @param redissonClient the Redisson client used for Redis operations
   */
  public SemaphoreInitializer(@NonNull RedissonClient redissonClient) {
    this.redissonClient = redissonClient;
  }

  /**
   * Validates that the same semaphore key is not used with different permits values within this
   * JVM.
   *
   * @param semaphoreKey the full semaphore key including prefix
   * @param permits the number of permits configured for this call
   * @param methodName the method name for error messages, or null if not available
   * @throws SemaphoreConfigurationException if the key is used with inconsistent permit counts
   */
  public void validatePermitsConsistency(
      @NonNull String semaphoreKey, int permits, @Nullable String methodName) {
    Integer existingPermits = keyToPermits.putIfAbsent(semaphoreKey, permits);
    if (existingPermits != null && existingPermits != permits) {
      String location = methodName != null ? " (in " + methodName + ")" : "";
      throw new SemaphoreConfigurationException(
          String.format(
              "Semaphore key '%s' is used with inconsistent permits: %d (existing) vs %d%s. "
                  + "Each key must have the same permits across all usages.",
              semaphoreKey, existingPermits, permits, location),
          semaphoreKey);
    }
  }

  /**
   * Ensures the semaphore is initialized in Redis with the configured permits. Uses metadata
   * storage to detect and warn about permit mismatches across deployments.
   *
   * @param semaphoreKey the full semaphore key including prefix
   * @param permits the number of permits to initialize with
   */
  public void ensureInitialized(@NonNull String semaphoreKey, int permits) {
    if (initializedKeys.containsKey(semaphoreKey)) {
      return;
    }

    String metaKey = semaphoreKey + META_SUFFIX;
    RBucket<Integer> metaBucket = redissonClient.getBucket(metaKey);
    RPermitExpirableSemaphore semaphore = redissonClient.getPermitExpirableSemaphore(semaphoreKey);

    Integer existingPermits = metaBucket.get();

    if (existingPermits == null) {
      boolean created = semaphore.trySetPermits(permits);
      if (created) {
        metaBucket.set(permits);
        LOG.info("Created semaphore [{}] with {} permits", semaphoreKey, permits);
      } else {
        // Race condition: another instance created it between our check and set
        existingPermits = metaBucket.get();
        if (existingPermits != null && existingPermits != permits) {
          LOG.warn(
              "Semaphore [{}] was created by another instance with {} permits, "
                  + "but this instance configured {} permits. Using existing value. "
                  + "To change: delete Redis keys '{}' and '{}', then redeploy all instances.",
              semaphoreKey,
              existingPermits,
              permits,
              semaphoreKey,
              metaKey);
        }
      }
    } else if (existingPermits != permits) {
      LOG.warn(
          "Semaphore [{}] exists with {} permits, but this instance configured {} permits. "
              + "Using existing value. To change: delete Redis keys '{}' and '{}', "
              + "then redeploy all instances.",
          semaphoreKey,
          existingPermits,
          permits,
          semaphoreKey,
          metaKey);
    }

    initializedKeys.put(semaphoreKey, Boolean.TRUE);
  }
}
