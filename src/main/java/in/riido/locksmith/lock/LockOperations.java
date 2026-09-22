package in.riido.locksmith.lock;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import in.riido.locksmith.LockType;
import in.riido.locksmith.autoconfigure.LocksmithProperties;
import in.riido.locksmith.metrics.LocksmithMetrics;
import in.riido.locksmith.metrics.LocksmithMetrics.Outcome;
import in.riido.locksmith.metrics.LocksmithMetrics.Primitive;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.redisson.api.RFuture;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Acquires distributed locks over Redis. Every lock lives at the Redis key {@code
 * <keyPrefix>lock:<key>}; read and write locks of one key share it.
 *
 * <pre>{@code
 * try (LockHandle lock = locks.key("report:" + id).waitTime(Duration.ofSeconds(5)).acquire()) {
 *   if (lock.acquired()) {
 *     // critical section
 *   }
 * }
 * }</pre>
 *
 * <p>Redisson exceptions, for example when Redis is unreachable, propagate unchanged from every
 * method; they are never treated as "not acquired".
 */
public class LockOperations {

  private static final Logger LOG = LoggerFactory.getLogger(LockOperations.class);

  private static final String KEY_NAMESPACE = "lock:";

  private final @NonNull RedissonClient redisson;
  private final @NonNull LocksmithProperties properties;
  private final @NonNull LocksmithMetrics metrics;

  /**
   * Creates the operations.
   *
   * @param redisson the client every lock is taken through
   * @param properties supplies the key prefix
   * @param metrics records acquire attempts and hold times
   */
  public LockOperations(
      @NonNull RedissonClient redisson,
      @NonNull LocksmithProperties properties,
      @NonNull LocksmithMetrics metrics) {
    this.redisson = redisson;
    this.properties = properties;
    this.metrics = metrics;
  }

  /**
   * Starts building an acquire of the lock on a key. Defaults: {@link LockType#REENTRANT}, wait
   * time {@link Duration#ZERO} (try once), no lease time (the lock is renewed while held).
   *
   * @param key the key without prefix
   * @return a builder for the acquire
   */
  public @NonNull Builder key(@NonNull String key) {
    return new Builder(key);
  }

  /**
   * Reports whether any thread on any instance holds the lock on a key.
   *
   * @param key the key without prefix
   * @param type the lock type; {@code READ} and {@code WRITE} report on the shared key
   * @return {@code true} if the lock is held
   */
  public boolean isLocked(@NonNull String key, @NonNull LockType type) {
    return getLock(prefix(key), type).isLocked();
  }

  private @NonNull String prefix(@NonNull String key) {
    return properties.keyPrefix() + KEY_NAMESPACE + key;
  }

  private @NonNull RLock getLock(@NonNull String fullKey, @NonNull LockType type) {
    return switch (type) {
      case REENTRANT -> redisson.getLock(fullKey);
      case READ -> redisson.getReadWriteLock(fullKey).readLock();
      case WRITE -> redisson.getReadWriteLock(fullKey).writeLock();
    };
  }

  /** Configures and performs one acquire of a lock. Obtain it from {@link LockOperations#key}. */
  public final class Builder {

    private final @NonNull String key;
    private @NonNull LockType type = LockType.REENTRANT;
    private @NonNull Duration waitTime = Duration.ZERO;
    private @Nullable Duration leaseTime;

    private Builder(@NonNull String key) {
      this.key = key;
    }

    /**
     * Sets the lock type.
     *
     * @param type the lock type
     * @return this builder
     */
    public @NonNull Builder type(@NonNull LockType type) {
      this.type = type;
      return this;
    }

    /**
     * Sets how long {@link #acquire()} waits for the lock. Zero means try once and give up.
     *
     * @param waitTime the maximum wait
     * @return this builder
     * @throws IllegalArgumentException if {@code waitTime} is negative
     */
    public @NonNull Builder waitTime(@NonNull Duration waitTime) {
      requireNotNegative("waitTime", waitTime);
      this.waitTime = waitTime;
      return this;
    }

    /**
     * Sets a fixed lease: the lock expires this long after it is acquired and is not renewed.
     * Without a lease time the lock is renewed for as long as it is held.
     *
     * @param leaseTime the fixed lease
     * @return this builder
     * @throws IllegalArgumentException if {@code leaseTime} is shorter than one millisecond,
     *     including zero and negative values
     */
    public @NonNull Builder leaseTime(@NonNull Duration leaseTime) {
      if (leaseTime.toMillis() <= 0) {
        throw new IllegalArgumentException(
            "leaseTime must be at least one millisecond, got " + leaseTime);
      }
      this.leaseTime = leaseTime;
      return this;
    }

    /**
     * Tries to acquire the lock, waiting up to the wait time. Never throws for "not acquired": the
     * returned handle reports the outcome through {@link LockHandle#acquired()}. If the thread is
     * interrupted while waiting, its interrupt flag is restored and the handle is unacquired.
     *
     * <p>The handle must be closed on the thread that called this method.
     *
     * @return the handle, acquired or not
     * @throws RuntimeException any Redisson exception, unchanged, for example when Redis is
     *     unreachable
     */
    public @NonNull LockHandle acquire() {
      String fullKey = prefix(key);
      RLock lock = getLock(fullKey, type);
      long lease = leaseTime == null ? -1 : leaseTime.toMillis();
      long startNanos = System.nanoTime();
      boolean acquired;
      try {
        acquired = lock.tryLock(waitTime.toMillis(), lease, MILLISECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return notAcquired(fullKey, Outcome.INTERRUPTED, startNanos);
      }
      if (!acquired) {
        return notAcquired(fullKey, Outcome.SKIPPED, startNanos);
      }
      long acquiredAtNanos = System.nanoTime();
      Duration waited = Duration.ofNanos(acquiredAtNanos - startNanos);
      return recordAcquired(
          new LockHandle(lock, fullKey, leaseTime, acquiredAtNanos, waited, metrics), waited);
    }

    /**
     * Like {@link #acquire()}, but the lock is owned by {@code ownerId} instead of the calling
     * thread, so the handle can be released from any thread with {@link LockHandle#releaseAsync()}.
     * The calling thread waits for the outcome. If it is interrupted while waiting, its interrupt
     * flag is restored, the handle is unacquired, and a lock the pending attempt still takes is
     * released again.
     */
    @NonNull LockHandle acquire(long ownerId) {
      String fullKey = prefix(key);
      RLock lock = getLock(fullKey, type);
      long lease = leaseTime == null ? -1 : leaseTime.toMillis();
      long startNanos = System.nanoTime();
      RFuture<Boolean> attempt =
          lock.tryLockAsync(waitTime.toMillis(), lease, MILLISECONDS, ownerId);
      boolean acquired;
      try {
        acquired = attempt.toCompletableFuture().get();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        attempt.thenAccept(
            late -> {
              if (late) {
                lock.unlockAsync(ownerId);
              }
            });
        return notAcquired(fullKey, Outcome.INTERRUPTED, startNanos);
      } catch (ExecutionException e) {
        // The same conversion as Redisson's synchronous calls, so both paths throw alike.
        throw e.getCause() instanceof RedisException redis
            ? redis
            : new RedisException("Unexpected exception while processing command", e.getCause());
      }
      if (!acquired) {
        return notAcquired(fullKey, Outcome.SKIPPED, startNanos);
      }
      long acquiredAtNanos = System.nanoTime();
      Duration waited = Duration.ofNanos(acquiredAtNanos - startNanos);
      return recordAcquired(
          new LockHandle(lock, ownerId, fullKey, leaseTime, acquiredAtNanos, waited, metrics),
          waited);
    }

    private @NonNull LockHandle recordAcquired(
        @NonNull LockHandle handle, @NonNull Duration waited) {
      try {
        metrics.recordAcquire(Primitive.LOCK, Outcome.ACQUIRED, waited);
      } catch (RuntimeException e) {
        handle.close();
        throw e;
      }
      return handle;
    }

    private @NonNull LockHandle notAcquired(
        @NonNull String fullKey, @NonNull Outcome outcome, long startNanos) {
      Duration waited = Duration.ofNanos(System.nanoTime() - startNanos);
      metrics.recordAcquire(Primitive.LOCK, outcome, waited);
      LOG.debug(
          "Lock [{}] not acquired after waiting {}ms: {}",
          fullKey,
          waited.toMillis(),
          outcome.tagValue());
      return new LockHandle(null, fullKey, leaseTime, 0L, waited, metrics);
    }

    private static void requireNotNegative(@NonNull String name, @NonNull Duration value) {
      if (value.isNegative()) {
        throw new IllegalArgumentException(name + " must not be negative, got " + value);
      }
    }
  }
}
