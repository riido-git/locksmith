package in.riido.locksmith.template;

import in.riido.locksmith.AcquisitionMode;
import in.riido.locksmith.autoconfigure.LocksmithProperties;
import in.riido.locksmith.autoconfigure.LocksmithProperties.SemaphoreProperties;
import in.riido.locksmith.exception.SemaphoreConfigurationException;
import in.riido.locksmith.metrics.SemaphoreMetrics;
import in.riido.locksmith.support.SemaphoreInitializer;
import in.riido.locksmith.template.callback.SemaphoreCallback;
import in.riido.locksmith.template.handle.PermitHandle;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.redisson.api.RPermitExpirableSemaphore;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Template class for programmatic distributed semaphore operations.
 *
 * <p>This class provides a builder-based programmatic API for distributed semaphores, complementing
 * the annotation-based approach provided by {@link in.riido.locksmith.DistributedSemaphore}. Use
 * this when you need more control over permit acquisition and release, or when annotations are not
 * suitable.
 *
 * <p>All methods apply the configured key prefix automatically. For example, if the key prefix is
 * "semaphore:" and you use {@code withKey("my-key")}, the actual Redis key will be
 * "semaphore:my-key".
 *
 * <h2>Try-with-resources (recommended)</h2>
 *
 * <pre>{@code
 * try (PermitHandle handle = semaphoreTemplate.withKey("pool")
 *         .permits(5)
 *         .tryAcquire()) {
 *     if (handle.isAcquired()) {
 *         // Use the resource - permit auto-released on close
 *     }
 * }
 * }</pre>
 *
 * <h2>Callback-based</h2>
 *
 * <pre>{@code
 * String result = semaphoreTemplate.withKey("pool")
 *     .permits(5)
 *     .execute(() -> "executed");
 * }</pre>
 *
 * <h2>Builder with custom configuration</h2>
 *
 * <pre>{@code
 * try (PermitHandle handle = semaphoreTemplate.withKey("pool")
 *         .permits(5)
 *         .waitTime(Duration.ofSeconds(10))
 *         .leaseTime(Duration.ofMinutes(2))
 *         .tryAcquire()) {
 *     if (handle.isAcquired()) {
 *         // work
 *     }
 * }
 * }</pre>
 *
 * @author Garvit Joshi
 * @since 2.1.0
 * @see SemaphoreCallback
 * @see PermitHandle
 * @see SemaphoreOperationBuilder
 * @see in.riido.locksmith.DistributedSemaphore
 */
public class LocksmithSemaphoreTemplate {

  private static final Logger LOG = LoggerFactory.getLogger(LocksmithSemaphoreTemplate.class);

  private final RedissonClient redissonClient;
  private final SemaphoreProperties semaphoreProperties;
  @Nullable private final SemaphoreMetrics semaphoreMetrics;
  private final SemaphoreInitializer semaphoreInitializer;

  /**
   * Constructs a new LocksmithSemaphoreTemplate.
   *
   * @param redissonClient the Redisson client for Redis operations
   * @param properties the configuration properties
   */
  public LocksmithSemaphoreTemplate(
      @NonNull RedissonClient redissonClient, @NonNull LocksmithProperties properties) {
    this(redissonClient, properties, null);
  }

  /**
   * Constructs a new LocksmithSemaphoreTemplate with optional metrics support.
   *
   * @param redissonClient the Redisson client for Redis operations
   * @param properties the configuration properties
   * @param semaphoreMetrics the optional semaphore metrics for observability
   */
  public LocksmithSemaphoreTemplate(
      @NonNull RedissonClient redissonClient,
      @NonNull LocksmithProperties properties,
      @Nullable SemaphoreMetrics semaphoreMetrics) {
    this.redissonClient = redissonClient;
    this.semaphoreProperties = properties.semaphore();
    this.semaphoreMetrics = semaphoreMetrics;
    this.semaphoreInitializer = new SemaphoreInitializer(redissonClient);
  }

  // ========== Builder Entry Point ==========

  /**
   * Creates a builder for semaphore operations on the specified key.
   *
   * <p><b>Important:</b> You must call {@link SemaphoreOperationBuilder#permits(int)} before
   * calling any terminal operation ({@code tryAcquire()} or {@code execute()}).
   *
   * <pre>{@code
   * // Try-with-resources
   * try (PermitHandle handle = semaphoreTemplate.withKey("pool")
   *         .permits(5)
   *         .waitTime(Duration.ofSeconds(10))
   *         .tryAcquire()) {
   *     if (handle.isAcquired()) {
   *         // work
   *     }
   * }
   *
   * // Callback-based
   * semaphoreTemplate.withKey("pool")
   *     .permits(5)
   *     .execute(() -> doWork());
   * }</pre>
   *
   * @param key the semaphore key (prefix will be applied automatically)
   * @return a builder for configuring and executing semaphore operations
   */
  @NonNull
  public SemaphoreOperationBuilder withKey(@NonNull String key) {
    return new SemaphoreOperationBuilder(key);
  }

  // ========== Standalone Operations ==========

  /**
   * Releases a permit back to the semaphore.
   *
   * <p>Prefer using {@link PermitHandle} with try-with-resources for automatic release. This method
   * is provided for cases where manual release is needed.
   *
   * @param key the semaphore key (prefix will be applied automatically)
   * @param permitId the permit ID returned by {@link PermitHandle#permitId()}
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
      LOG.warn("Failed to release permit [{}] from [{}]", permitId, fullKey, e);
    }
  }

  // ========== Internal Methods ==========

  @NonNull
  private PermitHandle doTryAcquirePermit(
      @NonNull String key,
      int permits,
      @NonNull Duration waitTime,
      @NonNull Duration leaseTime,
      boolean immediateMode) {
    String fullKey = semaphoreProperties.keyPrefix() + key;
    semaphoreInitializer.validatePermitsConsistency(fullKey, permits, null);
    semaphoreInitializer.ensureInitialized(fullKey, permits);

    RPermitExpirableSemaphore semaphore = redissonClient.getPermitExpirableSemaphore(fullKey);
    long startTime = System.currentTimeMillis();

    try {
      String permitId =
          semaphore.tryAcquire(waitTime.toMillis(), leaseTime.toMillis(), TimeUnit.MILLISECONDS);
      if (permitId != null) {
        if (semaphoreMetrics != null) {
          semaphoreMetrics.recordAcquisitionTime(System.currentTimeMillis() - startTime);
          semaphoreMetrics.recordAcquired();
        }
        LOG.debug("Permit [{}] acquired from semaphore [{}]", permitId, fullKey);
        return PermitHandle.acquired(permitId, semaphore, fullKey, semaphoreMetrics);
      } else {
        if (semaphoreMetrics != null) {
          semaphoreMetrics.recordSkipped(
              immediateMode ? AcquisitionMode.SKIP_IMMEDIATELY : AcquisitionMode.WAIT_AND_SKIP);
        }
        LOG.debug("Failed to acquire permit from semaphore [{}]", fullKey);
        return PermitHandle.notAcquired();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      if (semaphoreMetrics != null) {
        semaphoreMetrics.recordSkipped(
            immediateMode ? AcquisitionMode.SKIP_IMMEDIATELY : AcquisitionMode.WAIT_AND_SKIP);
      }
      LOG.warn("Thread interrupted while waiting for permit from [{}]", fullKey);
      return PermitHandle.notAcquired();
    }
  }

  @Nullable
  private <T> T doExecuteWithPermit(
      @NonNull String key,
      int permits,
      @NonNull Duration waitTime,
      @NonNull Duration leaseTime,
      boolean immediateMode,
      @NonNull SemaphoreCallback<T> callback)
      throws Exception {
    try (PermitHandle handle =
        doTryAcquirePermit(key, permits, waitTime, leaseTime, immediateMode)) {
      if (!handle.isAcquired()) {
        String fullKey = semaphoreProperties.keyPrefix() + key;
        LOG.debug("Failed to acquire permit from [{}] for callback execution", fullKey);
        return null;
      }
      String fullKey = semaphoreProperties.keyPrefix() + key;
      LOG.debug(
          "Permit [{}] acquired from [{}] for callback execution", handle.permitId(), fullKey);
      return callback.execute();
    }
  }

  // ========== Builder Class ==========

  /**
   * Builder for configuring and executing semaphore operations.
   *
   * <p>This builder provides a fluent API for semaphore operations with custom configuration. Use
   * {@link LocksmithSemaphoreTemplate#withKey(String)} to create an instance.
   *
   * <p><b>Important:</b> {@link #permits(int)} must be called before any terminal operation.
   *
   * <p>Example usage:
   *
   * <pre>{@code
   * // Try-with-resources
   * try (PermitHandle handle = semaphoreTemplate.withKey("pool")
   *         .permits(5)
   *         .waitTime(Duration.ofSeconds(10))
   *         .leaseTime(Duration.ofMinutes(2))
   *         .tryAcquire()) {
   *     if (handle.isAcquired()) {
   *         // work
   *     }
   * }
   *
   * // Callback-based
   * String result = semaphoreTemplate.withKey("pool")
   *     .permits(5)
   *     .execute(() -> doWork());
   * }</pre>
   *
   * @since 2.1.0
   */
  public class SemaphoreOperationBuilder {

    private static final int PERMITS_NOT_SET = -1;

    private final String key;
    private int permits = PERMITS_NOT_SET;
    private Duration waitTime = Duration.ZERO;
    private Duration leaseTime = semaphoreProperties.leaseTime();

    private SemaphoreOperationBuilder(@NonNull String key) {
      this.key = key;
    }

    /**
     * Sets the total number of permits for this semaphore.
     *
     * <p>This must be called before {@link #tryAcquire()} or {@link #execute(SemaphoreCallback)}.
     *
     * @param permits the total number of permits (must be positive)
     * @return this builder for chaining
     */
    @NonNull
    public SemaphoreOperationBuilder permits(int permits) {
      this.permits = permits;
      return this;
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
     * <p>Returns a {@link PermitHandle} that implements {@link AutoCloseable} for use with
     * try-with-resources. The permit is automatically released when the handle is closed.
     *
     * @return a handle representing the acquisition result
     * @throws SemaphoreConfigurationException if permits was not set or is not positive
     */
    @NonNull
    public PermitHandle tryAcquire() {
      validatePermits();
      boolean immediateMode = waitTime.isZero();
      return doTryAcquirePermit(key, permits, waitTime, leaseTime, immediateMode);
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
     * @throws SemaphoreConfigurationException if permits was not set or is not positive
     */
    @Nullable
    public <T> T execute(@NonNull SemaphoreCallback<T> callback) throws Exception {
      validatePermits();
      boolean immediateMode = waitTime.isZero();
      return doExecuteWithPermit(key, permits, waitTime, leaseTime, immediateMode, callback);
    }

    private void validatePermits() {
      if (permits == PERMITS_NOT_SET) {
        throw new SemaphoreConfigurationException(
            "permits() must be called before tryAcquire() or execute()", key);
      }
      if (permits <= 0) {
        throw new SemaphoreConfigurationException("Permits must be positive, got: " + permits, key);
      }
    }
  }
}
