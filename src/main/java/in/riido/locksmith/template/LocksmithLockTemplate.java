package in.riido.locksmith.template;

import in.riido.locksmith.LockType;
import in.riido.locksmith.autoconfigure.LocksmithProperties;
import in.riido.locksmith.autoconfigure.LocksmithProperties.LockProperties;
import in.riido.locksmith.metrics.LockMetrics;
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
 * <p>This class provides a programmatic API for distributed locks, complementing the
 * annotation-based approach provided by {@link in.riido.locksmith.DistributedLock}. Use this when
 * you need more control over lock acquisition and release, or when annotations are not suitable.
 *
 * <p>All methods apply the configured key prefix automatically. For example, if the key prefix is
 * "lock:" and you call {@code tryLock("my-key")}, the actual Redis key will be "lock:my-key".
 *
 * <h2>Simple Usage</h2>
 *
 * <pre>{@code
 * // Acquire and release
 * if (lockTemplate.tryLock("my-key")) {
 *     try {
 *         // Critical section
 *     } finally {
 *         lockTemplate.unlock("my-key");
 *     }
 * }
 *
 * // Callback-based (recommended)
 * String result = lockTemplate.executeWithLock("my-key", () -> {
 *     return "executed";
 * });
 * }</pre>
 *
 * <h2>Builder for Complex Cases</h2>
 *
 * <pre>{@code
 * // With custom timing and lock type
 * boolean acquired = lockTemplate.forKey("my-key")
 *     .waitTime(Duration.ofSeconds(5))
 *     .leaseTime(Duration.ofMinutes(2))
 *     .lockType(LockType.WRITE)
 *     .tryLock();
 *
 * // With auto-renew
 * String result = lockTemplate.forKey("my-key")
 *     .autoRenew()
 *     .execute(() -> {
 *         // Long-running operation
 *         return "done";
 *     });
 * }</pre>
 *
 * @author Garvit Joshi
 * @since 2.1.0
 * @see LockCallback
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

  // ========== Simple Methods ==========

  /**
   * Tries to acquire a reentrant lock immediately without waiting.
   *
   * <p>Uses the default lease time from configuration. For more control, use {@link
   * #forKey(String)}.
   *
   * @param key the lock key (prefix will be applied automatically)
   * @return true if the lock was acquired, false otherwise
   */
  public boolean tryLock(@NonNull String key) {
    return doTryLock(key, Duration.ZERO, lockProperties.leaseTime(), LockType.REENTRANT, true);
  }

  /**
   * Releases a reentrant lock.
   *
   * <p>For other lock types, use {@link #forKey(String)}.
   *
   * @param key the lock key (prefix will be applied automatically)
   */
  public void unlock(@NonNull String key) {
    doUnlock(key, LockType.REENTRANT);
  }

  /**
   * Checks if a reentrant lock is currently held by any thread/instance.
   *
   * <p>For other lock types, use {@link #forKey(String)}.
   *
   * @param key the lock key (prefix will be applied automatically)
   * @return true if the lock is held by anyone, false otherwise
   */
  public boolean isLocked(@NonNull String key) {
    return doIsLocked(key, LockType.REENTRANT);
  }

  /**
   * Executes a callback while holding a reentrant lock.
   *
   * <p>Tries to acquire the lock immediately without waiting. Uses the default lease time from
   * configuration. For more control, use {@link #forKey(String)}.
   *
   * @param <T> the type of result returned by the callback
   * @param key the lock key (prefix will be applied automatically)
   * @param callback the callback to execute while holding the lock
   * @return the result of the callback, or null if the lock could not be acquired
   * @throws Exception if the callback throws an exception
   */
  @Nullable
  public <T> T executeWithLock(@NonNull String key, @NonNull LockCallback<T> callback)
      throws Exception {
    return doExecuteWithLock(
        key, Duration.ZERO, lockProperties.leaseTime(), LockType.REENTRANT, true, callback);
  }

  // ========== Builder Entry Point ==========

  /**
   * Creates a builder for lock operations on the specified key.
   *
   * <p>Use the builder when you need to customize wait time, lease time, lock type, or enable
   * auto-renew.
   *
   * <pre>{@code
   * // Custom timing
   * lockTemplate.forKey("my-key")
   *     .waitTime(Duration.ofSeconds(5))
   *     .leaseTime(Duration.ofMinutes(2))
   *     .tryLock();
   *
   * // Auto-renew with write lock
   * lockTemplate.forKey("my-key")
   *     .lockType(LockType.WRITE)
   *     .autoRenew()
   *     .execute(() -> doWork());
   * }</pre>
   *
   * @param key the lock key (prefix will be applied automatically)
   * @return a builder for configuring and executing lock operations
   */
  @NonNull
  public LockOperationBuilder forKey(@NonNull String key) {
    return new LockOperationBuilder(key);
  }

  // ========== Internal Methods ==========

  private boolean doTryLock(
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
      } else {
        if (lockMetrics != null) {
          String reason = immediateMode ? "immediate" : "timeout";
          lockMetrics.recordSkipped(reason);
        }
        LOG.debug("Failed to acquire lock [{}] with type={}", fullKey, lockType);
      }
      return acquired;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      if (lockMetrics != null) {
        String reason = immediateMode ? "immediate" : "timeout";
        lockMetrics.recordSkipped(reason);
      }
      LOG.warn("Thread interrupted while waiting for lock [{}]", fullKey);
      return false;
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
    }
  }

  private boolean doIsLocked(@NonNull String key, @NonNull LockType lockType) {
    String fullKey = lockProperties.keyPrefix() + key;
    RLock lock = getLock(fullKey, lockType);
    return lock.isLocked();
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
    String fullKey = lockProperties.keyPrefix() + key;
    RLock lock = getLock(fullKey, lockType);
    long acquisitionStartTime = System.currentTimeMillis();

    boolean acquired = false;
    try {
      acquired = lock.tryLock(waitTime.toMillis(), leaseTime.toMillis(), TimeUnit.MILLISECONDS);

      if (!acquired) {
        if (lockMetrics != null) {
          String reason = immediateMode ? "immediate" : "timeout";
          lockMetrics.recordSkipped(reason);
        }
        LOG.debug("Failed to acquire lock [{}] for callback execution", fullKey);
        return null;
      }

      if (lockMetrics != null) {
        lockMetrics.recordAcquisitionTime(System.currentTimeMillis() - acquisitionStartTime);
        lockMetrics.recordAcquired();
      }

      LOG.debug("Lock [{}] acquired for callback execution", fullKey);
      long heldStartTime = System.currentTimeMillis();
      T result = callback.execute();
      if (lockMetrics != null) {
        lockMetrics.recordHeldTime(System.currentTimeMillis() - heldStartTime);
      }
      return result;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      if (lockMetrics != null) {
        String reason = immediateMode ? "immediate" : "timeout";
        lockMetrics.recordSkipped(reason);
      }
      LOG.warn("Thread interrupted while waiting for lock [{}]", fullKey);
      return null;
    } finally {
      if (acquired) {
        try {
          lock.unlock();
          LOG.debug("Lock [{}] released after callback execution", fullKey);
        } catch (IllegalMonitorStateException e) {
          LOG.warn(
              "Lock [{}] was already released (possibly expired): {}", fullKey, e.getMessage());
        }
      }
    }
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
   * LocksmithLockTemplate#forKey(String)} to create an instance.
   *
   * <p>Example usage:
   *
   * <pre>{@code
   * // Acquire with custom timing
   * boolean acquired = lockTemplate.forKey("my-key")
   *     .waitTime(Duration.ofSeconds(5))
   *     .leaseTime(Duration.ofMinutes(2))
   *     .tryLock();
   *
   * // Execute with auto-renew and write lock
   * String result = lockTemplate.forKey("my-key")
   *     .lockType(LockType.WRITE)
   *     .autoRenew()
   *     .execute(() -> {
   *         // Long-running operation
   *         return "done";
   *     });
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
     * @return true if the lock was acquired, false otherwise
     */
    public boolean tryLock() {
      boolean immediateMode = waitTime.isZero();
      return doTryLock(key, waitTime, leaseTime, lockType, immediateMode);
    }

    /**
     * Releases the lock with the configured lock type.
     *
     * <p>Note: Only the lock type setting affects this operation.
     */
    public void unlock() {
      doUnlock(key, lockType);
    }

    /**
     * Checks if the lock is currently held by any thread/instance.
     *
     * <p>Note: Only the lock type setting affects this operation.
     *
     * @return true if the lock is held by anyone, false otherwise
     */
    public boolean isLocked() {
      return doIsLocked(key, lockType);
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
