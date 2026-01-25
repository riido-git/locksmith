package in.riido.locksmith.template;

import in.riido.locksmith.autoconfigure.LocksmithProperties;
import in.riido.locksmith.autoconfigure.LocksmithProperties.SemaphoreProperties;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.redisson.api.RBucket;
import org.redisson.api.RPermitExpirableSemaphore;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Template class for programmatic distributed semaphore operations.
 *
 * <p>This class provides a programmatic API for distributed semaphores, complementing the
 * annotation-based approach provided by {@link in.riido.locksmith.DistributedSemaphore}. Use this
 * when you need more control over permit acquisition and release, or when annotations are not
 * suitable.
 *
 * <p>All methods apply the configured key prefix automatically. For example, if the key prefix is
 * "semaphore:" and you call {@code tryAcquirePermit("my-key", 5)}, the actual Redis key will be
 * "semaphore:my-key".
 *
 * <h2>Simple Usage</h2>
 *
 * <pre>{@code
 * // Acquire and release
 * String permitId = semaphoreTemplate.tryAcquirePermit("my-key", 5);
 * if (permitId != null) {
 *     try {
 *         // Critical section
 *     } finally {
 *         semaphoreTemplate.releasePermit("my-key", permitId);
 *     }
 * }
 *
 * // Callback-based (recommended)
 * String result = semaphoreTemplate.executeWithPermit("my-key", 5, () -> {
 *     return "executed";
 * });
 * }</pre>
 *
 * <h2>Builder for Complex Cases</h2>
 *
 * <pre>{@code
 * // With custom timing
 * String permitId = semaphoreTemplate.forKey("my-key", 5)
 *     .waitTime(Duration.ofSeconds(10))
 *     .leaseTime(Duration.ofMinutes(2))
 *     .tryAcquire();
 *
 * // Execute with custom timing
 * String result = semaphoreTemplate.forKey("my-key", 5)
 *     .waitTime(Duration.ofSeconds(10))
 *     .execute(() -> {
 *         return "done";
 *     });
 * }</pre>
 *
 * @author Garvit Joshi
 * @since 2.1.0
 * @see SemaphoreCallback
 * @see SemaphoreOperationBuilder
 * @see in.riido.locksmith.DistributedSemaphore
 */
public class LocksmithSemaphoreTemplate {

  private static final Logger LOG = LoggerFactory.getLogger(LocksmithSemaphoreTemplate.class);
  private static final String META_SUFFIX = ":meta";

  private final RedissonClient redissonClient;
  private final SemaphoreProperties semaphoreProperties;

  /** Cache to track which keys have been initialized in Redis by this JVM. */
  private final Map<String, Boolean> initializedKeys = new ConcurrentHashMap<>();

  /**
   * Constructs a new LocksmithSemaphoreTemplate.
   *
   * @param redissonClient the Redisson client for Redis operations
   * @param properties the configuration properties
   */
  public LocksmithSemaphoreTemplate(
      @NonNull RedissonClient redissonClient, @NonNull LocksmithProperties properties) {
    this.redissonClient = redissonClient;
    this.semaphoreProperties = properties.semaphore();
  }

  // ========== Simple Methods ==========

  /**
   * Tries to acquire a permit immediately without waiting.
   *
   * <p>Uses the default lease time from configuration. The semaphore will be initialized with the
   * specified number of permits if it doesn't exist. For more control, use {@link #forKey(String,
   * int)}.
   *
   * @param key the semaphore key (prefix will be applied automatically)
   * @param permits the total number of permits for this semaphore
   * @return the permit ID if acquired, or null if no permit was available
   */
  @Nullable
  public String tryAcquirePermit(@NonNull String key, int permits) {
    return doTryAcquirePermit(key, permits, Duration.ZERO, semaphoreProperties.leaseTime());
  }

  /**
   * Releases a permit back to the semaphore.
   *
   * @param key the semaphore key (prefix will be applied automatically)
   * @param permitId the permit ID returned by tryAcquirePermit
   */
  public void releasePermit(@NonNull String key, @NonNull String permitId) {
    String fullKey = semaphoreProperties.keyPrefix() + key;
    RPermitExpirableSemaphore semaphore = redissonClient.getPermitExpirableSemaphore(fullKey);

    try {
      semaphore.release(permitId);
      LOG.debug("Permit [{}] released from semaphore [{}]", permitId, fullKey);
    } catch (IllegalArgumentException e) {
      LOG.warn(
          "Failed to release permit [{}] from [{}] - permit may have expired: {}",
          permitId,
          fullKey,
          e.getMessage());
    } catch (Exception e) {
      LOG.warn("Failed to release permit [{}] from [{}]: {}", permitId, fullKey, e.getMessage());
    }
  }

  /**
   * Executes a callback while holding a semaphore permit.
   *
   * <p>Tries to acquire a permit immediately without waiting. Uses the default lease time from
   * configuration. For more control, use {@link #forKey(String, int)}.
   *
   * @param <T> the type of result returned by the callback
   * @param key the semaphore key (prefix will be applied automatically)
   * @param permits the total number of permits for this semaphore
   * @param callback the callback to execute while holding the permit
   * @return the result of the callback, or null if a permit could not be acquired
   * @throws Exception if the callback throws an exception
   */
  @Nullable
  public <T> T executeWithPermit(
      @NonNull String key, int permits, @NonNull SemaphoreCallback<T> callback) throws Exception {
    return doExecuteWithPermit(
        key, permits, Duration.ZERO, semaphoreProperties.leaseTime(), callback);
  }

  // ========== Builder Entry Point ==========

  /**
   * Creates a builder for semaphore operations on the specified key.
   *
   * <p>Use the builder when you need to customize wait time or lease time.
   *
   * <pre>{@code
   * // Custom timing
   * String permitId = semaphoreTemplate.forKey("my-key", 5)
   *     .waitTime(Duration.ofSeconds(10))
   *     .leaseTime(Duration.ofMinutes(2))
   *     .tryAcquire();
   *
   * // Execute with custom wait time
   * semaphoreTemplate.forKey("my-key", 5)
   *     .waitTime(Duration.ofSeconds(10))
   *     .execute(() -> doWork());
   * }</pre>
   *
   * @param key the semaphore key (prefix will be applied automatically)
   * @param permits the total number of permits for this semaphore
   * @return a builder for configuring and executing semaphore operations
   */
  @NonNull
  public SemaphoreOperationBuilder forKey(@NonNull String key, int permits) {
    return new SemaphoreOperationBuilder(key, permits);
  }

  // ========== Internal Methods ==========

  @Nullable
  private String doTryAcquirePermit(
      @NonNull String key, int permits, @NonNull Duration waitTime, @NonNull Duration leaseTime) {
    if (permits <= 0) {
      throw new IllegalArgumentException("Permits must be positive, got: " + permits);
    }

    String fullKey = semaphoreProperties.keyPrefix() + key;
    ensureSemaphoreInitialized(fullKey, permits);

    RPermitExpirableSemaphore semaphore = redissonClient.getPermitExpirableSemaphore(fullKey);

    try {
      String permitId =
          semaphore.tryAcquire(waitTime.toMillis(), leaseTime.toMillis(), TimeUnit.MILLISECONDS);
      if (permitId != null) {
        LOG.debug("Permit [{}] acquired from semaphore [{}]", permitId, fullKey);
      } else {
        LOG.debug("Failed to acquire permit from semaphore [{}]", fullKey);
      }
      return permitId;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOG.warn("Thread interrupted while waiting for permit from [{}]", fullKey);
      return null;
    }
  }

  @Nullable
  private <T> T doExecuteWithPermit(
      @NonNull String key,
      int permits,
      @NonNull Duration waitTime,
      @NonNull Duration leaseTime,
      @NonNull SemaphoreCallback<T> callback)
      throws Exception {
    if (permits <= 0) {
      throw new IllegalArgumentException("Permits must be positive, got: " + permits);
    }

    String fullKey = semaphoreProperties.keyPrefix() + key;
    ensureSemaphoreInitialized(fullKey, permits);

    RPermitExpirableSemaphore semaphore = redissonClient.getPermitExpirableSemaphore(fullKey);

    String permitId = null;
    try {
      permitId =
          semaphore.tryAcquire(waitTime.toMillis(), leaseTime.toMillis(), TimeUnit.MILLISECONDS);

      if (permitId == null) {
        LOG.debug("Failed to acquire permit from [{}] for callback execution", fullKey);
        return null;
      }

      LOG.debug("Permit [{}] acquired from [{}] for callback execution", permitId, fullKey);
      return callback.execute();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOG.warn("Thread interrupted while waiting for permit from [{}]", fullKey);
      return null;
    } finally {
      if (permitId != null) {
        try {
          semaphore.release(permitId);
          LOG.debug("Permit [{}] released from [{}] after callback execution", permitId, fullKey);
        } catch (IllegalArgumentException e) {
          LOG.warn(
              "Permit [{}] was already released (possibly expired) from [{}]: {}",
              permitId,
              fullKey,
              e.getMessage());
        } catch (Exception e) {
          LOG.warn(
              "Failed to release permit [{}] from [{}]: {}", permitId, fullKey, e.getMessage());
        }
      }
    }
  }

  /**
   * Ensures the semaphore is initialized in Redis with the configured permits.
   *
   * <p>Uses metadata storage to detect and warn about permit mismatches across deployments. This
   * method is thread-safe and will only initialize each semaphore once per JVM.
   *
   * @param fullKey the full semaphore key including prefix
   * @param permits the number of permits to initialize with
   */
  private void ensureSemaphoreInitialized(@NonNull String fullKey, int permits) {
    if (initializedKeys.containsKey(fullKey)) {
      return;
    }

    String metaKey = fullKey + META_SUFFIX;
    RBucket<Integer> metaBucket = redissonClient.getBucket(metaKey);
    RPermitExpirableSemaphore semaphore = redissonClient.getPermitExpirableSemaphore(fullKey);

    Integer existingPermits = metaBucket.get();

    if (existingPermits == null) {
      boolean created = semaphore.trySetPermits(permits);
      if (created) {
        metaBucket.set(permits);
        LOG.info("Created semaphore [{}] with {} permits", fullKey, permits);
      } else {
        existingPermits = metaBucket.get();
        if (existingPermits != null && existingPermits != permits) {
          LOG.warn(
              "Semaphore [{}] was created by another instance with {} permits, "
                  + "but this instance configured {} permits. Using existing value. "
                  + "To change: delete Redis keys '{}' and '{}', then restart.",
              fullKey,
              existingPermits,
              permits,
              fullKey,
              metaKey);
        }
      }
    } else if (existingPermits != permits) {
      LOG.warn(
          "Semaphore [{}] exists with {} permits, but this instance configured {} permits. "
              + "Using existing value. To change: delete Redis keys '{}' and '{}', then restart.",
          fullKey,
          existingPermits,
          permits,
          fullKey,
          metaKey);
    }

    initializedKeys.put(fullKey, Boolean.TRUE);
  }

  // ========== Builder Class ==========

  /**
   * Builder for configuring and executing semaphore operations.
   *
   * <p>This builder provides a fluent API for semaphore operations with custom configuration. Use
   * {@link LocksmithSemaphoreTemplate#forKey(String, int)} to create an instance.
   *
   * <p>Example usage:
   *
   * <pre>{@code
   * // Acquire with custom timing
   * String permitId = semaphoreTemplate.forKey("my-key", 5)
   *     .waitTime(Duration.ofSeconds(10))
   *     .leaseTime(Duration.ofMinutes(2))
   *     .tryAcquire();
   *
   * // Execute with custom wait time
   * String result = semaphoreTemplate.forKey("my-key", 5)
   *     .waitTime(Duration.ofSeconds(10))
   *     .execute(() -> {
   *         return "done";
   *     });
   * }</pre>
   *
   * @since 2.1.0
   */
  public class SemaphoreOperationBuilder {

    private final String key;
    private final int permits;
    private Duration waitTime = Duration.ZERO;
    private Duration leaseTime = semaphoreProperties.leaseTime();

    private SemaphoreOperationBuilder(@NonNull String key, int permits) {
      this.key = key;
      this.permits = permits;
    }

    /**
     * Sets the maximum time to wait for a permit.
     *
     * <p>Default is {@link Duration#ZERO} (no waiting).
     *
     * @param waitTime the maximum wait time
     * @return this builder for chaining
     */
    @NonNull
    public SemaphoreOperationBuilder waitTime(@NonNull Duration waitTime) {
      this.waitTime = waitTime;
      return this;
    }

    /**
     * Sets the lease time after which the permit is automatically released.
     *
     * <p>Default is the configured lease time from properties.
     *
     * @param leaseTime the lease time
     * @return this builder for chaining
     */
    @NonNull
    public SemaphoreOperationBuilder leaseTime(@NonNull Duration leaseTime) {
      this.leaseTime = leaseTime;
      return this;
    }

    /**
     * Tries to acquire a permit with the configured settings.
     *
     * @return the permit ID if acquired, or null if no permit was available
     */
    @Nullable
    public String tryAcquire() {
      return doTryAcquirePermit(key, permits, waitTime, leaseTime);
    }

    /**
     * Executes a callback while holding a permit with the configured settings.
     *
     * <p>The permit is automatically released after the callback completes, even if an exception is
     * thrown.
     *
     * @param <T> the type of result returned by the callback
     * @param callback the callback to execute while holding the permit
     * @return the result of the callback, or null if a permit could not be acquired
     * @throws Exception if the callback throws an exception
     */
    @Nullable
    public <T> T execute(@NonNull SemaphoreCallback<T> callback) throws Exception {
      return doExecuteWithPermit(key, permits, waitTime, leaseTime, callback);
    }
  }
}
