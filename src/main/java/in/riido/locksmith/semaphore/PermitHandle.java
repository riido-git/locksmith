package in.riido.locksmith.semaphore;

import in.riido.locksmith.metrics.LocksmithMetrics;
import in.riido.locksmith.metrics.LocksmithMetrics.Primitive;
import in.riido.locksmith.support.RedissonFutures;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.redisson.RedissonShutdownException;
import org.redisson.api.RPermitExpirableSemaphore;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The result of one {@link SemaphoreOperations.Builder#acquire()}. Use it in try-with-resources and
 * check {@link #acquired()} before starting the bounded work.
 */
public final class PermitHandle implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(PermitHandle.class);

  private final @NonNull RedissonClient redisson;
  private final @Nullable RPermitExpirableSemaphore semaphore;
  private final @Nullable String permitId;
  private final @NonNull String fullKey;
  private final @NonNull Duration lease;
  private final long acquiredAtNanos;
  private final @NonNull Duration waited;
  private final @NonNull LocksmithMetrics metrics;
  private final AtomicBoolean closed = new AtomicBoolean();

  PermitHandle(
      @NonNull RedissonClient redisson,
      @Nullable RPermitExpirableSemaphore semaphore,
      @Nullable String permitId,
      @NonNull String fullKey,
      @NonNull Duration lease,
      long acquiredAtNanos,
      @NonNull Duration waited,
      @NonNull LocksmithMetrics metrics) {
    this.redisson = redisson;
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
   * handle, or closing a second time, does nothing. Works on any thread and waits for the release,
   * also when the thread is interrupted, whose flag is kept; on a Redisson I/O or timer thread it
   * only starts the release, because waiting there can stall Redisson. Once the Redisson client is
   * shutting down, it stops waiting within about a second, because Redisson may never answer a
   * release it already sent; the permit then expires on its own. Never throws: a failed release,
   * for example because the lease ran out or Redis is unreachable, is logged as a WARN and the
   * permit expires on its own.
   */
  @Override
  public void close() {
    if (semaphore == null || permitId == null || !closed.compareAndSet(false, true)) {
      return;
    }
    Duration held = Duration.ofNanos(System.nanoTime() - acquiredAtNanos);
    CompletionStage<Void> release;
    try {
      release =
          semaphore
              .releaseAsync(permitId)
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
   * Logs a failed release, or records the hold time of a successful one. Redisson reports an
   * expired or unknown permit as an {@link IllegalArgumentException}, which gets one WARN that
   * names the possible causes without picking one: the hold time starts when the acquire returned,
   * but the lease started when the acquire was sent, so a hold shorter than the lease does not show
   * the lease had time left. A release refused because the client is shutting down is expected
   * then, so it gets one line without a stack trace.
   */
  private void released(@NonNull Duration held, @Nullable Throwable failure) {
    if (failure instanceof IllegalArgumentException notHeld) {
      LOG.warn(
          "Permit [{}] was reported as not held at release, {}ms after its acquire returned (lease"
              + " {}ms). Possible causes: the lease ran out, counted from when the acquire was sent,"
              + " so a slow Redis reply or a pause also uses it up, and another caller may then have"
              + " used this permit; the semaphore key was lost in Redis; a server's clock is ahead,"
              + " and callers on that server may then have used this permit; or Redisson resent a"
              + " release that had already succeeded after a slow Redis reply: {}",
          fullKey,
          held.toMillis(),
          lease.toMillis(),
          notHeld.getMessage());
      return;
    }
    if (failure instanceof RedissonShutdownException) {
      LOG.warn(
          "Permit [{}] release was not confirmed after {}ms (lease {}ms) because the Redisson client"
              + " is shutting down; the permit expires on its own",
          fullKey,
          held.toMillis(),
          lease.toMillis());
      return;
    }
    if (failure != null) {
      LOG.warn(
          "Permit [{}] release failed after {}ms (lease {}ms): {}",
          fullKey,
          held.toMillis(),
          lease.toMillis(),
          failure.getMessage(),
          failure);
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
}
