package in.riido.locksmith.semaphore;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import in.riido.locksmith.LocksmithConfigurationException;
import in.riido.locksmith.autoconfigure.LocksmithProperties;
import in.riido.locksmith.metrics.LocksmithMetrics;
import in.riido.locksmith.metrics.LocksmithMetrics.Outcome;
import in.riido.locksmith.metrics.LocksmithMetrics.Primitive;
import in.riido.locksmith.support.RedissonFutures;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
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
   * Returns the number of permits of a semaphore that are free right now, never below zero. After
   * the count is lowered below the number of permits held, it stays zero until enough of them are
   * released.
   *
   * @param key the key without prefix
   * @return the free permits
   * @throws IllegalStateException if called on a Redisson I/O thread ({@code redisson-netty-*}) or
   *     the timer thread ({@code redisson-timer-*}), for example inside a callback of a Redisson
   *     async call; nothing is sent to Redis then
   */
  public int availablePermits(@NonNull String key) {
    RedissonFutures.requireNotRedissonThread(false);
    // Redisson subtracts a lowered count from the free permits, so they go negative while held.
    return Math.max(0, redisson.getPermitExpirableSemaphore(prefix(key)).availablePermits());
  }

  private @NonNull String prefix(@NonNull String key) {
    return properties.keyPrefix() + KEY_NAMESPACE + key;
  }

  /**
   * Sets the permit count in Redis once per key per distinct value in this JVM. Redisson's {@code
   * setPermits} creates a missing semaphore, changes another count and leaves an equal one alone,
   * all in one atomic script, so racing threads and instances need no coordination here: the last
   * writer wins. The count is read first only for the INFO line on a change. The cache is read
   * without locking, and the Redis calls run outside any map operation, so no acquire ever waits on
   * another key's Redis calls.
   *
   * @throws InterruptedException if the thread is interrupted while a call is pending; the count is
   *     then set again on the next acquire
   */
  private void ensurePermits(
      @NonNull RPermitExpirableSemaphore semaphore, @NonNull String fullKey, int permits)
      throws InterruptedException {
    Integer cached = appliedPermits.get(fullKey);
    if (cached != null && cached == permits) {
      return;
    }
    int current = RedissonFutures.await(redisson, semaphore.getPermitsAsync());
    if (current != permits) {
      RedissonFutures.await(redisson, semaphore.setPermitsAsync(permits));
      if (current != 0) {
        LOG.info("Semaphore [{}] permits changed from {} to {}", fullKey, current, permits);
      }
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
     * {@link PermitHandle#acquired()}. If the thread is interrupted before or while the count is
     * set or the permit is waited for, its interrupt flag is kept, the handle is unacquired, and no
     * permit is left taken in Redis. Only an acquire that completed before the interrupt could stop
     * it returns an acquired handle, with the flag set. An interrupt already set when this is
     * called sends nothing to Redis.
     *
     * <p>Semaphores are not reentrant: a nested acquire of the same key on the same thread takes
     * another permit.
     *
     * @return the handle, acquired or not
     * @throws LocksmithConfigurationException if the permit count was not set or is below one, or,
     *     on a Redis Cluster client, if the key contains a brace that forms no hash tag such as
     *     {@code {42}}; nothing is sent to Redis then
     * @throws IllegalStateException if called on a Redisson I/O thread ({@code redisson-netty-*})
     *     or the timer thread ({@code redisson-timer-*}), for example inside a callback of a
     *     Redisson async call, or with a wait time on any other Redisson thread, such as a topic
     *     listener; nothing is sent to Redis then
     * @throws RuntimeException any Redisson exception, unchanged, for example when Redis is
     *     unreachable
     */
    public @NonNull PermitHandle acquire() {
      String fullKey = prefix(key);
      if (permits < 1) {
        throw new LocksmithConfigurationException(
            "Semaphore [" + fullKey + "] permits must be set to at least 1, got " + permits);
      }
      RedissonFutures.requireNotRedissonThread(waitTime.toMillis() > 0);
      RedissonFutures.requireClusterSafeKey(redisson, fullKey);
      long startNanos = System.nanoTime();
      if (Thread.currentThread().isInterrupted()) {
        // Nothing is sent: an attempt would take the permit briefly, then its cancel undoes it.
        return notAcquired(fullKey, Outcome.INTERRUPTED, startNanos);
      }
      RPermitExpirableSemaphore semaphore = redisson.getPermitExpirableSemaphore(fullKey);
      String permitId;
      try {
        ensurePermits(semaphore, fullKey, permits);
        long waitMillis = waitTime.toMillis();
        permitId = tryAcquire(semaphore, waitMillis);
        // A semaphore always has at least one permit, so a count of 0 means Redis lost its state:
        // the count key, or the set of held permits. setPermits restores the count either way.
        if (permitId == null && RedissonFutures.await(redisson, semaphore.getPermitsAsync()) == 0) {
          RedissonFutures.await(redisson, semaphore.setPermitsAsync(permits));
          LOG.info(
              "Semaphore [{}] had no permits in Redis; count set to {} again", fullKey, permits);
          long elapsedMillis = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
          long leftMillis = Math.max(0L, waitMillis - elapsedMillis);
          permitId = tryAcquire(semaphore, leftMillis);
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
              redisson, semaphore, permitId, fullKey, leaseTime, acquiredAtNanos, waited, metrics);
      try {
        metrics.recordAcquire(Primitive.SEMAPHORE, Outcome.ACQUIRED, waited);
      } catch (RuntimeException e) {
        handle.close();
        throw e;
      }
      return handle;
    }

    /**
     * Tries once to acquire one permit through Redisson's async API. The list variant is used
     * because its future is the one Redisson completes, so cancelling it on an interrupt makes
     * Redisson release a permit the attempt still wins; the single-permit variant returns a derived
     * future that a cancel does not reach.
     *
     * @return the permit id, or null if none was acquired within {@code waitMillis}
     * @throws InterruptedException if the thread is interrupted, including before the call
     */
    private @Nullable String tryAcquire(
        @NonNull RPermitExpirableSemaphore semaphore, long waitMillis) throws InterruptedException {
      List<String> ids =
          RedissonFutures.await(
              redisson,
              semaphore.tryAcquireAsync(1, waitMillis, leaseTime.toMillis(), MILLISECONDS));
      return ids.isEmpty() ? null : ids.get(0);
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
      return new PermitHandle(redisson, null, null, fullKey, leaseTime, 0L, waited, metrics);
    }
  }
}
