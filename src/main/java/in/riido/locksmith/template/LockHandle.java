package in.riido.locksmith.template;

import in.riido.locksmith.metrics.LockMetrics;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.redisson.api.RLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An {@link AutoCloseable} handle representing the result of a lock acquisition attempt.
 *
 * <p>This class enables the try-with-resources pattern for distributed locks, ensuring automatic
 * release when the handle is closed:
 *
 * <pre>{@code
 * try (LockHandle handle = lockTemplate.withKey("my-key").tryLock()) {
 *     if (handle.isAcquired()) {
 *         // Critical section - lock is held
 *     }
 * }
 * // Lock is automatically released here
 * }</pre>
 *
 * <p>If the lock was not acquired, {@link #close()} is a safe no-op.
 *
 * @author Garvit Joshi
 * @since 4.0.0
 * @see LocksmithLockTemplate
 */
public class LockHandle implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(LockHandle.class);

  private final boolean acquired;
  @Nullable private final RLock lock;
  @Nullable private final String fullKey;
  @Nullable private final LockMetrics lockMetrics;
  private final long heldStartTime;

  private LockHandle(
      boolean acquired,
      @Nullable RLock lock,
      @Nullable String fullKey,
      @Nullable LockMetrics lockMetrics) {
    this.acquired = acquired;
    this.lock = lock;
    this.fullKey = fullKey;
    this.lockMetrics = lockMetrics;
    this.heldStartTime = acquired ? System.currentTimeMillis() : 0;
  }

  /**
   * Creates a handle representing a successful lock acquisition.
   *
   * @param lock the acquired lock
   * @param fullKey the full Redis key of the lock
   * @param lockMetrics the optional lock metrics
   * @return a handle with {@code isAcquired() == true}
   */
  @NonNull
  static LockHandle acquired(
      @NonNull RLock lock, @NonNull String fullKey, @Nullable LockMetrics lockMetrics) {
    return new LockHandle(true, lock, fullKey, lockMetrics);
  }

  /**
   * Creates a handle representing a failed lock acquisition.
   *
   * @return a handle with {@code isAcquired() == false}
   */
  @NonNull
  static LockHandle notAcquired() {
    return new LockHandle(false, null, null, null);
  }

  /**
   * Returns whether the lock was successfully acquired.
   *
   * @return true if the lock is held by this handle, false otherwise
   */
  public boolean isAcquired() {
    return acquired;
  }

  /**
   * Releases the lock if it was acquired. Safe to call multiple times or if the lock was never
   * acquired (no-op in both cases).
   */
  @Override
  public void close() {
    if (!acquired || lock == null) {
      return;
    }
    try {
      lock.unlock();
      if (lockMetrics != null) {
        lockMetrics.recordHeldTime(System.currentTimeMillis() - heldStartTime);
      }
      LOG.debug("Lock [{}] released via handle", fullKey);
    } catch (IllegalMonitorStateException e) {
      LOG.warn("Lock [{}] was already released (possibly expired): {}", fullKey, e.getMessage());
    } catch (Exception e) {
      LOG.warn("Failed to release lock [{}]", fullKey, e);
    }
  }
}
