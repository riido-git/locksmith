package in.riido.locksmith.template;

import in.riido.locksmith.LockType;
import in.riido.locksmith.autoconfigure.LocksmithProperties;
import in.riido.locksmith.autoconfigure.LocksmithProperties.LockProperties;
import in.riido.locksmith.metrics.LockMetrics;
import in.riido.locksmith.template.callback.LockCallback;
import in.riido.locksmith.template.handle.LockHandle;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Template class for programmatic distributed lock operations.
 *
 * <p>This class provides a builder-based programmatic API for distributed locks, complementing the
 * annotation-based approach provided by {@link in.riido.locksmith.DistributedLock}. Use this when
 * you need more control over lock acquisition and release, or when annotations are not suitable.
 *
 * <p>All methods apply the configured key prefix automatically. For example, if the key prefix is
 * "lock:" and you use {@code withKey("my-key")}, the actual Redis key will be "lock:my-key".
 *
 * <h2>Try-with-resources (recommended)</h2>
 *
 * <pre>{@code
 * try (LockHandle handle = lockTemplate.withKey("my-key").tryLock()) {
 *     if (handle.isAcquired()) {
 *         // Critical section - lock auto-released on close
 *     }
 * }
 * }</pre>
 *
 * <h2>Callback-based</h2>
 *
 * <pre>{@code
 * String result = lockTemplate.withKey("my-key")
 *     .waitTime(Duration.ofSeconds(5))
 *     .execute(() -> "executed");
 * }</pre>
 *
 * <h2>Builder with custom configuration</h2>
 *
 * <pre>{@code
 * try (LockHandle handle = lockTemplate.withKey("my-key")
 *         .waitTime(Duration.ofSeconds(5))
 *         .leaseTime(Duration.ofMinutes(2))
 *         .lockType(LockType.WRITE)
 *         .tryLock()) {
 *     if (handle.isAcquired()) {
 *         // work
 *     }
 * }
 *
 * // With auto-renew
 * String result = lockTemplate.withKey("my-key")
 *     .autoRenew()
 *     .execute(() -> longRunningOperation());
 * }</pre>
 *
 * @author Garvit Joshi
 * @since 2.1.0
 * @see LockCallback
 * @see LockHandle
 * @see LockOperationBuilder
 * @see in.riido.locksmith.DistributedLock
 */
public class LocksmithLockTemplate {

  private static final Logger LOG = LoggerFactory.getLogger(LocksmithLockTemplate.class);

  private final RedissonClient redissonClient;
  private final LockProperties lockProperties;
  @Nullable private final LockMetrics lockMetrics;

  /**
   * Constructs a new LocksmithLockTemplate.
   *
   * @param redissonClient the Redisson client for Redis operations
   * @param properties the configuration properties
   */
  public LocksmithLockTemplate(
      @NonNull RedissonClient redissonClient, @NonNull LocksmithProperties properties) {
    this(redissonClient, properties, null);
  }

  /**
   * Constructs a new LocksmithLockTemplate with optional metrics support.
   *
   * @param redissonClient the Redisson client for Redis operations
   * @param properties the configuration properties
   * @param lockMetrics the optional lock metrics for observability
   */
  public LocksmithLockTemplate(
      @NonNull RedissonClient redissonClient,
      @NonNull LocksmithProperties properties,
      @Nullable LockMetrics lockMetrics) {
    this.redissonClient = redissonClient;
    this.lockProperties = properties.lock();
    this.lockMetrics = lockMetrics;
  }

  // ========== Builder Entry Point ==========

  /**
   * Creates a builder for lock operations on the specified key.
   *
   * <pre>{@code
   * // Try-with-resources
   * try (LockHandle handle = lockTemplate.withKey("my-key")
   *         .waitTime(Duration.ofSeconds(5))
   *         .lockType(LockType.WRITE)
   *         .tryLock()) {
   *     if (handle.isAcquired()) {
   *         // Critical section
   *     }
   * }
   *
   * // Callback-based
   * String result = lockTemplate.withKey("my-key")
   *     .autoRenew()
   *     .execute(() -> doWork());
   * }</pre>
   *
   * @param key the lock key (prefix will be applied automatically)
   * @return a builder for configuring and executing lock operations
   */
  @NonNull
  public LockOperationBuilder withKey(@NonNull String key) {
    return new LockOperationBuilder(key);
  }

  // ========== Standalone Operations ==========

  /**
   * Releases a reentrant lock.
   *
   * <p>Prefer using {@link LockHandle} with try-with-resources for automatic release. This method
   * is provided for cases where manual release is needed.
   *
   * @param key the lock key (prefix will be applied automatically)
   */
  public void unlock(@NonNull String key) {
    doUnlock(key, LockType.REENTRANT);
  }

  /**
   * Releases a lock of the specified type.
   *
   * <p>Prefer using {@link LockHandle} with try-with-resources for automatic release. This method
   * is provided for cases where manual release is needed.
   *
   * @param key the lock key (prefix will be applied automatically)
   * @param lockType the type of lock to release
   */
  public void unlock(@NonNull String key, @NonNull LockType lockType) {
    doUnlock(key, lockType);
  }

  /**
   * Checks if a reentrant lock is currently held by any thread/instance.
   *
   * @param key the lock key (prefix will be applied automatically)
   * @return true if the lock is held by anyone, false otherwise
   */
  public boolean isLocked(@NonNull String key) {
    return doIsLocked(key, LockType.REENTRANT);
  }

  /**
   * Checks if a lock of the specified type is currently held by any thread/instance.
   *
   * @param key the lock key (prefix will be applied automatically)
   * @param lockType the type of lock to check
   * @return true if the lock is held by anyone, false otherwise
   */
  public boolean isLocked(@NonNull String key, @NonNull LockType lockType) {
    return doIsLocked(key, lockType);
  }

  // ========== Internal Methods ==========

  @NonNull
  private LockHandle doTryLock(
      @NonNull String key,
      @NonNull Duration waitTime,
      @NonNull Duration leaseTime,
      @NonNull LockType lockType,
      boolean immediateMode) {
    String fullKey = lockProperties.keyPrefix() + key;
    RLock lock = getLock(fullKey, lockType);
    long startTime = System.currentTimeMillis();

    try {
      boolean acquired =
          lock.tryLock(waitTime.toMillis(), leaseTime.toMillis(), TimeUnit.MILLISECONDS);
      if (acquired) {
        if (lockMetrics != null) {
          lockMetrics.recordAcquisitionTime(System.currentTimeMillis() - startTime);
          lockMetrics.recordAcquired();
        }
        LOG.debug("Lock [{}] acquired with type={}", fullKey, lockType);
        return LockHandle.acquired(lock, fullKey, lockMetrics);
      } else {
        if (lockMetrics != null) {
          String reason = immediateMode ? "immediate" : "timeout";
          lockMetrics.recordSkipped(reason);
        }
        LOG.debug("Failed to acquire lock [{}] with type={}", fullKey, lockType);
        return LockHandle.notAcquired();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      if (lockMetrics != null) {
        String reason = immediateMode ? "immediate" : "timeout";
        lockMetrics.recordSkipped(reason);
      }
      LOG.warn("Thread interrupted while waiting for lock [{}]", fullKey);
      return LockHandle.notAcquired();
    }
  }

  @Nullable
  private <T> T doExecuteWithLock(
      @NonNull String key,
      @NonNull Duration waitTime,
      @NonNull Duration leaseTime,
      @NonNull LockType lockType,
      boolean immediateMode,
      @NonNull LockCallback<T> callback)
      throws Exception {
    try (LockHandle handle = doTryLock(key, waitTime, leaseTime, lockType, immediateMode)) {
      if (!handle.isAcquired()) {
        LOG.debug(
            "Failed to acquire lock [{}] for callback execution", lockProperties.keyPrefix() + key);
        return null;
      }
      LOG.debug("Lock [{}] acquired for callback execution", lockProperties.keyPrefix() + key);
      return callback.execute();
    }
  }

  private void doUnlock(@NonNull String key, @NonNull LockType lockType) {
    String fullKey = lockProperties.keyPrefix() + key;
    RLock lock = getLock(fullKey, lockType);

    try {
      lock.unlock();
      LOG.debug("Lock [{}] released with type={}", fullKey, lockType);
    } catch (IllegalMonitorStateException e) {
      LOG.warn(
          "Failed to unlock [{}] - lock not held by current thread or already released: {}",
          fullKey,
          e.getMessage());
    } catch (Exception e) {
      LOG.warn("Failed to release lock [{}]", fullKey, e);
    }
  }

  private boolean doIsLocked(@NonNull String key, @NonNull LockType lockType) {
    String fullKey = lockProperties.keyPrefix() + key;
    RLock lock = getLock(fullKey, lockType);
    return lock.isLocked();
  }

  @NonNull
  private RLock getLock(@NonNull String fullKey, @NonNull LockType lockType) {
    return switch (lockType) {
      case REENTRANT -> redissonClient.getLock(fullKey);
      case READ -> redissonClient.getReadWriteLock(fullKey).readLock();
      case WRITE -> redissonClient.getReadWriteLock(fullKey).writeLock();
    };
  }

  // ========== Builder Class ==========

  /**
   * Builder for configuring and executing lock operations.
   *
   * <p>This builder provides a fluent API for lock operations with custom configuration. Use {@link
   * LocksmithLockTemplate#withKey(String)} to create an instance.
   *
   * <p>Example usage:
   *
   * <pre>{@code
   * // Try-with-resources
   * try (LockHandle handle = lockTemplate.withKey("my-key")
   *         .waitTime(Duration.ofSeconds(5))
   *         .leaseTime(Duration.ofMinutes(2))
   *         .tryLock()) {
   *     if (handle.isAcquired()) {
   *         // Critical section
   *     }
   * }
   *
   * // Callback with auto-renew and write lock
   * String result = lockTemplate.withKey("my-key")
   *     .lockType(LockType.WRITE)
   *     .autoRenew()
   *     .execute(() -> longRunningOperation());
   * }</pre>
   *
   * @since 2.1.0
   */
  public class LockOperationBuilder {

    private final String key;
    private Duration waitTime = Duration.ZERO;
    private Duration leaseTime = lockProperties.leaseTime();
    private LockType lockType = LockType.REENTRANT;
    private boolean autoRenewEnabled = false;

    private LockOperationBuilder(@NonNull String key) {
      this.key = key;
    }

    /**
     * Sets the maximum time to wait for the lock.
     *
     * <p>Default is {@link Duration#ZERO} (no waiting).
     *
     * @param waitTime the maximum wait time
     * @return this builder for chaining
     */
    @NonNull
    public LockOperationBuilder waitTime(@NonNull Duration waitTime) {
      this.waitTime = waitTime;
      return this;
    }

    /**
     * Sets the lease time after which the lock is automatically released.
     *
     * <p>Default is the configured lease time from properties. Use {@link #autoRenew()} instead of
     * setting a negative value.
     *
     * <p><b>Note:</b> Calling this method after {@link #autoRenew()} will disable auto-renew and a
     * warning will be logged.
     *
     * @param leaseTime the lease time
     * @return this builder for chaining
     */
    @NonNull
    public LockOperationBuilder leaseTime(@NonNull Duration leaseTime) {
      if (autoRenewEnabled) {
        LOG.warn(
            "leaseTime() called after autoRenew() for key [{}] - auto-renew will be disabled. "
                + "Remove leaseTime() call to use auto-renew, or remove autoRenew() to use fixed lease time.",
            key);
        autoRenewEnabled = false;
      }
      this.leaseTime = leaseTime;
      return this;
    }

    /**
     * Sets the type of lock to acquire.
     *
     * <p>Default is {@link LockType#REENTRANT}.
     *
     * @param lockType the lock type (REENTRANT, READ, or WRITE)
     * @return this builder for chaining
     */
    @NonNull
    public LockOperationBuilder lockType(@NonNull LockType lockType) {
      this.lockType = lockType;
      return this;
    }

    /**
     * Enables auto-renew mode using Redisson's watchdog.
     *
     * <p>When enabled, the lock will be automatically renewed while held, preventing expiration
     * during long-running operations. This is equivalent to setting lease time to -1ms.
     *
     * <p><b>Note:</b> Calling {@link #leaseTime(Duration)} after this method will disable
     * auto-renew.
     *
     * @return this builder for chaining
     */
    @NonNull
    public LockOperationBuilder autoRenew() {
      this.autoRenewEnabled = true;
      this.leaseTime = Duration.ofMillis(-1);
      return this;
    }

    /**
     * Tries to acquire the lock with the configured settings.
     *
     * <p>Returns a {@link LockHandle} that implements {@link AutoCloseable} for use with
     * try-with-resources. The lock is automatically released when the handle is closed.
     *
     * @return a handle representing the acquisition result
     */
    @NonNull
    public LockHandle tryLock() {
      boolean immediateMode = waitTime.isZero();
      return doTryLock(key, waitTime, leaseTime, lockType, immediateMode);
    }

    /**
     * Executes a callback while holding the lock with the configured settings.
     *
     * <p>The lock is automatically released after the callback completes, even if an exception is
     * thrown.
     *
     * @param <T> the type of result returned by the callback
     * @param callback the callback to execute while holding the lock
     * @return the result of the callback, or null if the lock could not be acquired
     * @throws Exception if the callback throws an exception
     */
    @Nullable
    public <T> T execute(@NonNull LockCallback<T> callback) throws Exception {
      boolean immediateMode = waitTime.isZero();
      return doExecuteWithLock(key, waitTime, leaseTime, lockType, immediateMode, callback);
    }
  }
}
