package in.riido.locksmith.semaphore;

import in.riido.locksmith.metrics.LocksmithMetrics;
import in.riido.locksmith.metrics.LocksmithMetrics.Primitive;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.redisson.api.RPermitExpirableSemaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The result of one {@link SemaphoreOperations.Builder#acquire()}. Use it in try-with-resources and
 * check {@link #acquired()} before starting the bounded work.
 */
public final class PermitHandle implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(PermitHandle.class);

  private final @Nullable RPermitExpirableSemaphore semaphore;
  private final @Nullable String permitId;
  private final @NonNull String fullKey;
  private final @NonNull Duration lease;
  private final long acquiredAtNanos;
  private final @NonNull Duration waited;
  private final @NonNull LocksmithMetrics metrics;
  private final AtomicBoolean closed = new AtomicBoolean();

  PermitHandle(
      @Nullable RPermitExpirableSemaphore semaphore,
      @Nullable String permitId,
      @NonNull String fullKey,
      @NonNull Duration lease,
      long acquiredAtNanos,
      @NonNull Duration waited,
      @NonNull LocksmithMetrics metrics) {
    this.semaphore = semaphore;
    this.permitId = permitId;
    this.fullKey = fullKey;
    this.lease = lease;
    this.acquiredAtNanos = acquiredAtNanos;
    this.waited = waited;
    this.metrics = metrics;
  }

  /**
   * Reports whether a permit was acquired.
   *
   * @return {@code true} if a permit was acquired
   */
  public boolean acquired() {
    return permitId != null;
  }

  /**
   * Returns the full Redis key of the semaphore, including the prefix.
   *
   * @return the full key
   */
  public @NonNull String key() {
    return fullKey;
  }

  /**
   * Returns the Redisson id of the acquired permit.
   *
   * @return the permit id, or null if no permit was acquired
   */
  public @Nullable String permitId() {
    return permitId;
  }

  /**
   * Releases the permit if it was acquired and records how long it was held. Closing an unacquired
   * handle, or closing a second time, does nothing. Never throws: a failed release, for example
   * because the lease ran out or Redis is unreachable, is logged as a WARN and the permit expires
   * on its own.
   */
  @Override
  public void close() {
    if (semaphore == null || permitId == null || !closed.compareAndSet(false, true)) {
      return;
    }
    Duration held = Duration.ofNanos(System.nanoTime() - acquiredAtNanos);
    try {
      semaphore.release(permitId);
    } catch (RuntimeException e) {
      released(held, e);
      return;
    }
    released(held, null);
  }

  /**
   * Releases the permit without blocking, so it is safe on any thread, including a Redisson I/O
   * thread where the blocking {@link #close()} is refused. Logs and records exactly as {@link
   * #close()} does. The returned stage completes once the release has finished and never completes
   * exceptionally. Releasing a second time, or an unacquired handle, completes at once and does
   * nothing.
   */
  @NonNull CompletionStage<Void> releaseAsync() {
    if (semaphore == null || permitId == null || !closed.compareAndSet(false, true)) {
      return CompletableFuture.completedFuture(null);
    }
    return semaphore
        .releaseAsync(permitId)
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
    if (failure != null) {
      IllegalArgumentException notHeld = notHeld(failure);
      if (notHeld != null) {
        LOG.warn(
            "Permit [{}] was no longer held at release after {}ms (lease {}ms); another instance"
                + " may have run concurrently: {}",
            fullKey,
            held.toMillis(),
            lease.toMillis(),
            notHeld.getMessage());
      } else {
        LOG.warn(
            "Permit [{}] release failed after {}ms (lease {}ms): {}",
            fullKey,
            held.toMillis(),
            lease.toMillis(),
            failure.getMessage(),
            failure);
      }
      return;
    }
    try {
      metrics.recordHeld(Primitive.SEMAPHORE, held);
    } catch (RuntimeException e) {
      LOG.warn("Permit [{}] metrics recording failed: {}", fullKey, e.getMessage());
    }
    LOG.debug(
        "Permit [{}] released after {}ms, waited {}ms",
        fullKey,
        held.toMillis(),
        waited.toMillis());
  }

  /**
   * Returns the {@link IllegalArgumentException} Redisson raises for an expired or unknown permit.
   * The synchronous {@code release} wraps it in a {@code RedisException}, so the cause is checked
   * too.
   */
  private static @Nullable IllegalArgumentException notHeld(@NonNull Throwable e) {
    if (e instanceof IllegalArgumentException direct) {
      return direct;
    }
    return e.getCause() instanceof IllegalArgumentException cause ? cause : null;
  }
}
