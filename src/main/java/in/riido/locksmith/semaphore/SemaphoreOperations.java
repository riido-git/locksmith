package in.riido.locksmith.semaphore;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import in.riido.locksmith.LocksmithConfigurationException;
import in.riido.locksmith.autoconfigure.LocksmithProperties;
import in.riido.locksmith.metrics.LocksmithMetrics;
import in.riido.locksmith.metrics.LocksmithMetrics.Outcome;
import in.riido.locksmith.metrics.LocksmithMetrics.Primitive;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.NonNull;
import org.redisson.api.RPermitExpirableSemaphore;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Acquires permits of distributed semaphores over Redis. Every semaphore lives at the Redis key
 * {@code <keyPrefix>semaphore:<key>}. A permit always has a fixed lease: it expires that long after
 * it is acquired, whether or not it was released.
 *
 * <pre>{@code
 * try (PermitHandle permit =
 *     semaphores.key("reports").permits(5).waitTime(Duration.ofSeconds(2)).acquire()) {
 *   if (permit.acquired()) {
 *     // bounded work
 *   }
 * }
 * }</pre>
 *
 * <p>The permit count is owned by the caller. The first acquire of a key in this JVM, and the first
 * acquire with a different count, sets the count in Redis: a new semaphore gets it, an existing one
 * with another count is changed to it, and the last writer wins.
 *
 * <p>Redisson exceptions, for example when Redis is unreachable, propagate unchanged from every
 * method; they are never treated as "not acquired".
 */
public class SemaphoreOperations {

  private static final Logger LOG = LoggerFactory.getLogger(SemaphoreOperations.class);

  private static final String KEY_NAMESPACE = "semaphore:";

  private final @NonNull RedissonClient redisson;
  private final @NonNull LocksmithProperties properties;
  private final @NonNull LocksmithMetrics metrics;
  private final Map<String, Integer> appliedPermits = new ConcurrentHashMap<>();

  /**
   * Creates the operations.
   *
   * @param redisson the client every permit is taken through
   * @param properties supplies the key prefix and the default lease time
   * @param metrics records acquire attempts and hold times
   */
  public SemaphoreOperations(
      @NonNull RedissonClient redisson,
      @NonNull LocksmithProperties properties,
      @NonNull LocksmithMetrics metrics) {
    this.redisson = redisson;
    this.properties = properties;
    this.metrics = metrics;
  }

  /**
   * Starts building an acquire of one permit of the semaphore on a key. The permit count must be
   * set. Defaults: wait time {@link Duration#ZERO} (try once), lease time {@code
   * locksmith.semaphore.lease-time}.
   *
   * @param key the key without prefix
   * @return a builder for the acquire
   */
  public @NonNull Builder key(@NonNull String key) {
    return new Builder(key);
  }

  /**
   * Returns the number of permits of a semaphore that are free right now.
   *
   * @param key the key without prefix
   * @return the free permits
   */
  public int availablePermits(@NonNull String key) {
    return redisson.getPermitExpirableSemaphore(prefix(key)).availablePermits();
  }

  private @NonNull String prefix(@NonNull String key) {
    return properties.keyPrefix() + KEY_NAMESPACE + key;
  }

  /**
   * Sets the permit count in Redis once per key per distinct value in this JVM: creates it when the
   * semaphore has no permits yet, changes it when Redis holds another count. If another instance
   * creates the semaphore between the read and the create, the count is read again and changed if
   * it differs, so this JVM is the last writer. The cache is read without locking, and the Redis
   * calls run outside any map operation, so no acquire ever waits on another key's Redis calls. Two
   * threads racing on the same key may both call Redis; that is harmless, as {@code trySetPermits}
   * is atomic and {@code setPermits} to the count Redis already holds changes nothing.
   */
  private void ensurePermits(
      @NonNull RPermitExpirableSemaphore semaphore, @NonNull String fullKey, int permits) {
    Integer cached = appliedPermits.get(fullKey);
    if (cached != null && cached == permits) {
      return;
    }
    int current = semaphore.getPermits();
    if (current == 0) {
      if (semaphore.trySetPermits(permits)) {
        appliedPermits.put(fullKey, permits);
        return;
      }
      current = semaphore.getPermits();
    }
    if (current != permits) {
      semaphore.setPermits(permits);
      LOG.info("Semaphore [{}] permits changed from {} to {}", fullKey, current, permits);
    }
    appliedPermits.put(fullKey, permits);
  }

  /**
   * Configures and performs one acquire of a permit. Obtain it from {@link
   * SemaphoreOperations#key}.
   */
  public final class Builder {

    private final @NonNull String key;
    private int permits;
    private @NonNull Duration waitTime = Duration.ZERO;
    private @NonNull Duration leaseTime = properties.semaphore().leaseTime();

    private Builder(@NonNull String key) {
      this.key = key;
    }

    /**
     * Sets the permit count of the semaphore. Required; must be at least one, which {@link
     * #acquire()} checks.
     *
     * @param permits the permit count
     * @return this builder
     */
    public @NonNull Builder permits(int permits) {
      this.permits = permits;
      return this;
    }

    /**
     * Sets how long {@link #acquire()} waits for a permit. Zero means try once and give up.
     *
     * @param waitTime the maximum wait
     * @return this builder
     * @throws IllegalArgumentException if {@code waitTime} is negative
     */
    public @NonNull Builder waitTime(@NonNull Duration waitTime) {
      if (waitTime.isNegative()) {
        throw new IllegalArgumentException("waitTime must not be negative, got " + waitTime);
      }
      this.waitTime = waitTime;
      return this;
    }

    /**
     * Sets the lease of the permit: it expires this long after it is acquired.
     *
     * @param leaseTime the lease
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
     * Tries to acquire one permit, waiting up to the wait time. Before the first acquire of a key,
     * and whenever the permit count differs from the one last applied in this JVM, the count is set
     * in Redis. Never throws for "not acquired": the returned handle reports the outcome through
     * {@link PermitHandle#acquired()}. If the thread is interrupted while waiting, its interrupt
     * flag is restored and the handle is unacquired.
     *
     * @return the handle, acquired or not
     * @throws LocksmithConfigurationException if the permit count was not set or is below one
     * @throws RuntimeException any Redisson exception, unchanged, for example when Redis is
     *     unreachable
     */
    public @NonNull PermitHandle acquire() {
      String fullKey = prefix(key);
      if (permits < 1) {
        throw new LocksmithConfigurationException(
            "Semaphore [" + fullKey + "] permits must be set to at least 1, got " + permits);
      }
      RPermitExpirableSemaphore semaphore = redisson.getPermitExpirableSemaphore(fullKey);
      ensurePermits(semaphore, fullKey, permits);
      long startNanos = System.nanoTime();
      String permitId;
      try {
        long waitMillis = waitTime.toMillis();
        permitId = semaphore.tryAcquire(waitMillis, leaseTime.toMillis(), MILLISECONDS);
        if (permitId == null && reinitialiseIfLost(semaphore, fullKey)) {
          long elapsedMillis = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
          long leftMillis = Math.max(0L, waitMillis - elapsedMillis);
          permitId = semaphore.tryAcquire(leftMillis, leaseTime.toMillis(), MILLISECONDS);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return notAcquired(fullKey, Outcome.INTERRUPTED, startNanos);
      }
      if (permitId == null) {
        return notAcquired(fullKey, Outcome.SKIPPED, startNanos);
      }
      long acquiredAtNanos = System.nanoTime();
      Duration waited = Duration.ofNanos(acquiredAtNanos - startNanos);
      PermitHandle handle =
          new PermitHandle(
              semaphore, permitId, fullKey, leaseTime, acquiredAtNanos, waited, metrics);
      try {
        metrics.recordAcquire(Primitive.SEMAPHORE, Outcome.ACQUIRED, waited);
      } catch (RuntimeException e) {
        handle.close();
        throw e;
      }
      return handle;
    }

    /**
     * Tells a full semaphore from one whose Redis state was lost: a semaphore always has at least
     * one permit, so {@code getPermits() == 0} means the key no longer exists. Then the cached
     * count is dropped and the count is set again.
     *
     * @return {@code true} if the semaphore was re-initialised and the acquire should be retried
     */
    private boolean reinitialiseIfLost(
        @NonNull RPermitExpirableSemaphore semaphore, @NonNull String fullKey) {
      if (semaphore.getPermits() != 0) {
        return false;
      }
      appliedPermits.remove(fullKey);
      ensurePermits(semaphore, fullKey, permits);
      return true;
    }

    private @NonNull PermitHandle notAcquired(
        @NonNull String fullKey, @NonNull Outcome outcome, long startNanos) {
      Duration waited = Duration.ofNanos(System.nanoTime() - startNanos);
      metrics.recordAcquire(Primitive.SEMAPHORE, outcome, waited);
      LOG.debug(
          "Permit [{}] not acquired after waiting {}ms: {}",
          fullKey,
          waited.toMillis(),
          outcome.tagValue());
      return new PermitHandle(null, null, fullKey, leaseTime, 0L, waited, metrics);
    }
  }
}
