package in.riido.locksmith.support;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import in.riido.locksmith.LocksmithConfigurationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.redisson.RedissonShutdownException;
import org.redisson.api.RFuture;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;

/**
 * The Redisson rules Locksmith's acquires and releases follow: which Redisson threads must not
 * block, which keys Redisson cannot keep in one Redis Cluster slot, and how to wait for an async
 * call. Locksmith acquires through Redisson's async API and waits here instead of calling
 * Redisson's blocking methods: those throw a {@code RedisException} when the thread is interrupted
 * but let the call finish in Redis, so a lock or permit is taken with no handle to release it.
 */
public final class RedissonFutures {

  /** How often a waiting call checks whether its client is shutting down. */
  private static final long SHUTDOWN_CHECK_MILLIS = 1000;

  /** Redisson's I/O threads: they read every Redis reply. */
  private static final String IO_THREAD = "redisson-netty";

  /** Redisson's timer thread: it runs every timeout and every lock renewal of the client. */
  private static final String TIMER_THREAD = "redisson-timer";

  /**
   * Any Redisson thread, including the executor threads that run listeners and executor service
   * tasks and deliver pub/sub messages, such as the one that wakes a waiting acquire.
   */
  private static final String REDISSON_THREAD = "redisson";

  private RedissonFutures() {}

  /**
   * Refuses a blocking call, such as an acquire, on a Redisson thread where it could stall
   * Redisson. Call it before anything is sent to Redis. A Redisson I/O thread is refused as
   * Redisson's blocking methods refuse it, and so is the timer thread, since blocking it stops
   * every timeout and lock renewal of the client. Any other Redisson thread, such as a topic
   * listener, is refused only for an acquire that waits, because the message that ends the wait is
   * delivered on those threads.
   *
   * @param waits whether the call may wait for a holder to release
   * @throws IllegalStateException on a Redisson I/O thread ({@code redisson-netty-*}) or the timer
   *     thread ({@code redisson-timer-*}), and on any other Redisson thread when {@code waits}
   */
  public static void requireNotRedissonThread(boolean waits) {
    String thread = Thread.currentThread().getName();
    if (thread.startsWith(IO_THREAD)) {
      // Mirrors the guard of org.redisson.command.CommandAsyncService.get in Redisson 4.8.0.
      throw new IllegalStateException(
          "Sync methods can't be invoked from async/rx/reactive listeners");
    }
    if (thread.startsWith(TIMER_THREAD)) {
      throw new IllegalStateException(
          "Locksmith cannot run on Redisson's timer thread ["
              + thread
              + "]: blocking it stops every timeout and lock renewal of the client. Call it from"
              + " another thread, for example with thenRunAsync.");
    }
    if (waits && thread.startsWith(REDISSON_THREAD)) {
      throw new IllegalStateException(
          "Locksmith cannot wait for a lock or permit on Redisson thread ["
              + thread
              + "]: Redisson delivers the message that ends the wait on its own threads, so the"
              + " wait can stall them. Call it from another thread, or use a wait time of zero.");
    }
  }

  /**
   * Reports whether the current thread must not wait for a Redis reply: a Redisson I/O thread,
   * which would deliver the reply, or the timer thread, which would time it out.
   *
   * @return {@code true} on a thread whose name starts with {@code redisson-netty} or {@code
   *     redisson-timer}
   */
  public static boolean onRedissonIoOrTimerThread() {
    String thread = Thread.currentThread().getName();
    return thread.startsWith(IO_THREAD) || thread.startsWith(TIMER_THREAD);
  }

  /**
   * Refuses, on a Redis Cluster client, a key that Redisson cannot keep in one slot. Redisson
   * places a lock's or a semaphore's companion keys in the key's slot by wrapping the key in
   * braces, but it takes a key that already contains an opening brace as carrying its own hash tag.
   * When that brace forms no tag, or when a key without an opening brace contains a closing one,
   * which ends Redisson's wrapping early, the companion keys land in other slots and Redis rejects
   * the calls that use them: a lock is taken but cannot be released, and a semaphore cannot be
   * acquired.
   *
   * @param redisson the client; its configuration is read only for a key with such a brace
   * @param fullKey the full Redis key, including the prefix
   * @throws LocksmithConfigurationException on a Cluster client, if the key contains an opening
   *     brace and the first closing brace after it is missing or directly follows it, or if it
   *     contains a closing brace and no opening one
   */
  public static void requireClusterSafeKey(
      @NonNull RedissonClient redisson, @NonNull String fullKey) {
    int open = fullKey.indexOf('{');
    if (open < 0 ? fullKey.indexOf('}') < 0 : fullKey.indexOf('}', open + 1) > open + 1) {
      return;
    }
    if (redisson.getConfig().isClusterConfig()) {
      throw new LocksmithConfigurationException(
          "Key ["
              + fullKey
              + "] contains a '{' or '}' that forms no Redis Cluster hash tag such as {42}:"
              + " Redisson would put its companion keys in other slots, and Redis Cluster would"
              + " reject the calls that use them. Remove the brace or complete the hash tag.");
    }
  }

  /**
   * Waits for a Redisson async call. If the thread is interrupted, the call is cancelled and the
   * {@link InterruptedException} is rethrown; Redisson then releases a lock or permit the cancelled
   * call still wins, provided the future is the one Redisson completes itself. If the call
   * completed before the cancel could reach it, its outcome stands and is returned with the
   * interrupt flag set, because dropping it would leave a lock or permit taken with no handle to
   * release it.
   *
   * <p>Every second of waiting, it checks whether the client is shutting down. Redisson's shutdown
   * stops the timer and the pub/sub delivery that complete a waiting lock or permit call, so such a
   * call would never complete. It is cancelled instead, as on an interrupt, and the wait fails with
   * Redisson's own {@link RedissonShutdownException}. A lock or permit the cancelled call still
   * wins then expires on its own, after the watchdog timeout or the lease, because a client that is
   * shutting down no longer sends the release.
   *
   * @param redisson the client the call was made on
   * @param future the pending call
   * @param <T> the result type
   * @return the result
   * @throws InterruptedException if the thread is interrupted, including before the call, and the
   *     call was cancelled
   * @throws RedissonShutdownException if the client shuts down while the call is pending
   * @throws RedisException what the call failed with, converted as Redisson's blocking methods do
   */
  public static <T> T await(@NonNull RedissonClient redisson, @NonNull RFuture<T> future)
      throws InterruptedException {
    CompletableFuture<T> pending = future.toCompletableFuture();
    while (true) {
      try {
        return pending.get(SHUTDOWN_CHECK_MILLIS, MILLISECONDS);
      } catch (TimeoutException e) {
        if (redisson.isShuttingDown() && future.cancel(false)) {
          throw new RedissonShutdownException("Redisson is shutdown");
        }
        // Still waiting, or the cancel lost to the completion and the next get returns at once.
      } catch (InterruptedException e) {
        if (future.cancel(false)) {
          throw e;
        }
        // The cancel lost to the completion, so the future is done: the next get returns at once.
        Thread.currentThread().interrupt();
      } catch (ExecutionException e) {
        // The same conversion as Redisson's blocking methods, so both paths throw alike.
        throw e.getCause() instanceof RedisException redis
            ? redis
            : new RedisException("Unexpected exception while processing command", e.getCause());
      }
    }
  }

  /**
   * Returns what a release failed with, without the {@link CompletionException}s around it.
   * Redisson can wrap a failure more than once: on a Redis Cluster client, a permit release refused
   * at shutdown arrives wrapped twice.
   *
   * @param failure what the release completed with, or null
   * @return the innermost cause, or {@code failure} if it is no {@code CompletionException}
   */
  public static @Nullable Throwable unwrap(@Nullable Throwable failure) {
    Throwable cause = failure;
    while (cause instanceof CompletionException wrapped && wrapped.getCause() != null) {
      cause = wrapped.getCause();
    }
    return cause;
  }

  /**
   * Waits for a release to finish. An interrupt does not end the wait; the interrupt flag is set
   * again when the wait ends.
   *
   * <p>Every second of waiting, it checks whether the client is shutting down, and stops waiting
   * once it is: Redisson's shutdown stops the timer that times out and retries a pending call, so a
   * release it already sent may never finish. The lock or permit then expires on its own, after the
   * watchdog timeout or the lease.
   *
   * @param redisson the client the release was sent on
   * @param release the pending release
   */
  public static void awaitRelease(
      @NonNull RedissonClient redisson, @NonNull CompletionStage<?> release) {
    CompletableFuture<?> pending = release.toCompletableFuture();
    boolean interrupted = false;
    try {
      while (true) {
        try {
          pending.get(SHUTDOWN_CHECK_MILLIS, MILLISECONDS);
          return;
        } catch (TimeoutException e) {
          if (redisson.isShuttingDown()) {
            return;
          }
        } catch (InterruptedException e) {
          interrupted = true;
        } catch (ExecutionException e) {
          // The release has finished; the stage that failed reports how.
          return;
        }
      }
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
