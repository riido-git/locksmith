package in.riido.locksmith.lock;

import in.riido.locksmith.metrics.LocksmithMetrics;
import in.riido.locksmith.metrics.LocksmithMetrics.Primitive;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.redisson.api.RLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The result of one {@link LockOperations.Builder#acquire()}. Use it in try-with-resources and
 * check {@link #acquired()} before entering the critical section.
 *
 * <p>The handle must be closed on the thread that acquired it, because Redisson ties lock ownership
 * to the acquiring thread. This is not enforced: closing on another thread fails the release, which
 * is logged as a WARN, and the lock stays until it expires.
 */
public final class LockHandle implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(LockHandle.class);

  private final @Nullable RLock lock;
  private final @Nullable Long ownerId;
  private final @NonNull String fullKey;
  private final @Nullable Duration fixedLease;
  private final long acquiredAtNanos;
  private final @NonNull Duration waited;
  private final @NonNull LocksmithMetrics metrics;
  private final AtomicBoolean closed = new AtomicBoolean();

  LockHandle(
      @Nullable RLock lock,
      @NonNull String fullKey,
      @Nullable Duration fixedLease,
      long acquiredAtNanos,
      @NonNull Duration waited,
      @NonNull LocksmithMetrics metrics) {
    this(lock, null, fullKey, fixedLease, acquiredAtNanos, waited, metrics);
  }

  /** A handle whose lock is owned by {@code ownerId}, or by the acquiring thread when null. */
  LockHandle(
      @Nullable RLock lock,
      @Nullable Long ownerId,
      @NonNull String fullKey,
      @Nullable Duration fixedLease,
      long acquiredAtNanos,
      @NonNull Duration waited,
      @NonNull LocksmithMetrics metrics) {
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
   * handle, or closing a second time, does nothing. Never throws: a failed release, for example
   * because a fixed lease ran out or Redis is unreachable, is logged as a WARN and the key expires
   * on its own.
   */
  @Override
  public void close() {
    if (ownerId != null) {
      releaseAsync().toCompletableFuture().join();
      return;
    }
    if (lock == null || !closed.compareAndSet(false, true)) {
      return;
    }
    Duration held = Duration.ofNanos(System.nanoTime() - acquiredAtNanos);
    try {
      lock.unlock();
    } catch (RuntimeException e) {
      released(held, e);
      return;
    }
    released(held, null);
  }

  /**
   * Releases a lock acquired with an owner id, from any thread, without blocking. Logs and records
   * exactly as {@link #close()} does. The returned stage completes once the release has finished
   * and never completes exceptionally. Closing a second time, or an unacquired handle, completes at
   * once and does nothing.
   */
  @NonNull CompletionStage<Void> releaseAsync() {
    if (lock == null || ownerId == null || !closed.compareAndSet(false, true)) {
      return CompletableFuture.completedFuture(null);
    }
    return lock.unlockAsync(ownerId)
        .handle(
            (ignored, failure) -> {
              Duration held = Duration.ofNanos(System.nanoTime() - acquiredAtNanos);
              released(
                  held,
                  failure instanceof CompletionException wrapped && wrapped.getCause() != null
                      ? wrapped.getCause()
                      : failure);
              return null;
            });
  }

  /** Logs a failed release, or records the hold time of a successful one. */
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
