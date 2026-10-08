package in.riido.locksmith.lock;

import in.riido.locksmith.metrics.LocksmithMetrics;
import in.riido.locksmith.metrics.LocksmithMetrics.Primitive;
import in.riido.locksmith.support.RedissonFutures;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.redisson.RedissonShutdownException;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The result of one {@link LockOperations.Builder#acquire()}. Use it in try-with-resources and
 * check {@link #acquired()} before entering the critical section.
 *
 * <p>The lock is owned by the thread that acquired it, so a nested acquire of the same key on that
 * thread is reentrant. The handle releases as that owner, so it can be closed on any thread. Until
 * it is closed, though, the acquiring thread takes the same key again at once, even for unrelated
 * work: after handing the handle to another thread, do not acquire that key again on the acquiring
 * thread.
 */
public final class LockHandle implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(LockHandle.class);

  private final @NonNull RedissonClient redisson;
  private final @Nullable RLock lock;
  private final long ownerId;
  private final @NonNull String fullKey;
  private final @Nullable Duration fixedLease;
  private final long acquiredAtNanos;
  private final @NonNull Duration waited;
  private final @NonNull LocksmithMetrics metrics;
  private final AtomicBoolean closed = new AtomicBoolean();

  /** A handle whose lock, when acquired, is owned by {@code ownerId} on {@code redisson}. */
  LockHandle(
      @NonNull RedissonClient redisson,
      @Nullable RLock lock,
      long ownerId,
      @NonNull String fullKey,
      @Nullable Duration fixedLease,
      long acquiredAtNanos,
      @NonNull Duration waited,
      @NonNull LocksmithMetrics metrics) {
    this.redisson = redisson;
    this.lock = lock;
    this.ownerId = ownerId;
    this.fullKey = fullKey;
    this.fixedLease = fixedLease;
    this.acquiredAtNanos = acquiredAtNanos;
    this.waited = waited;
    this.metrics = metrics;
  }

  /**
   * Reports whether the lock was acquired.
   *
   * @return {@code true} if the lock was acquired
   */
  public boolean acquired() {
    return lock != null;
  }

  /**
   * Returns the full Redis key of the lock, including the prefix.
   *
   * @return the full key
   */
  public @NonNull String key() {
    return fullKey;
  }

  /**
   * Releases the lock if it was acquired and records how long it was held. Closing an unacquired
   * handle, or closing a second time, does nothing. Works on any thread and waits for the release,
   * also when the thread is interrupted, whose flag is kept; on a Redisson I/O or timer thread it
   * only starts the release, because waiting there can stall Redisson. Once the Redisson client is
   * shutting down, it stops waiting within about a second, because Redisson may never answer a
   * release it already sent; the key then expires on its own. Never throws: a failed release, for
   * example because a fixed lease ran out or Redis is unreachable, is logged as a WARN and the key
   * expires on its own.
   */
  @Override
  public void close() {
    if (lock == null || !closed.compareAndSet(false, true)) {
      return;
    }
    Duration held = Duration.ofNanos(System.nanoTime() - acquiredAtNanos);
    CompletionStage<Void> release;
    try {
      release =
          lock.unlockAsync(ownerId)
              .handle(
                  (ignored, failure) -> {
                    released(held, RedissonFutures.unwrap(failure));
                    return null;
                  });
    } catch (RuntimeException e) {
      released(held, e);
      return;
    }
    if (!RedissonFutures.onRedissonIoOrTimerThread()) {
      RedissonFutures.awaitRelease(redisson, release);
    }
  }

  /**
   * Logs a failed release, or records the hold time of a successful one. A release refused because
   * the client is shutting down is expected then, so it gets one line without a stack trace.
   */
  private void released(@NonNull Duration held, @Nullable Throwable failure) {
    if (failure instanceof IllegalMonitorStateException e) {
      LOG.warn(
          "Lock [{}] was no longer held at release after {}ms ({}); another instance may have run"
              + " concurrently: {}",
          fullKey,
          held.toMillis(),
          describeLease(),
          e.getMessage());
      return;
    }
    if (failure instanceof RedissonShutdownException) {
      LOG.warn(
          "Lock [{}] release was not confirmed after {}ms ({}) because the Redisson client is"
              + " shutting down; the key expires on its own",
          fullKey,
          held.toMillis(),
          describeLease());
      return;
    }
    if (failure != null) {
      LOG.warn(
          "Lock [{}] release failed after {}ms ({}): {}",
          fullKey,
          held.toMillis(),
          describeLease(),
          failure.getMessage(),
          failure);
      return;
    }
    try {
      metrics.recordHeld(Primitive.LOCK, held);
    } catch (RuntimeException e) {
      LOG.warn("Lock [{}] metrics recording failed: {}", fullKey, e.getMessage());
    }
    LOG.debug(
        "Lock [{}] released after {}ms, waited {}ms", fullKey, held.toMillis(), waited.toMillis());
  }

  private @NonNull String describeLease() {
    return fixedLease == null ? "fixed lease none" : "fixed lease " + fixedLease.toMillis() + "ms";
  }
}
