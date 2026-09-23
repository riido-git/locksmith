package in.riido.locksmith.lock;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadLocalRandom;
import org.jspecify.annotations.NonNull;

/**
 * Internal API. Not for use by adopters. May change without notice.
 *
 * <p>Takes and releases locks that are not tied to a thread, for annotated methods that return a
 * {@code CompletionStage}: such a lock is released when the stage completes, often on another
 * thread. Each acquire gets a generated owner id in place of the thread id Redisson normally uses.
 * The id is negative, so it never equals the id of a thread, and a lock taken this way is not
 * reentrant.
 */
public final class AsyncLockSupport {

  private AsyncLockSupport() {}

  /**
   * Acquires the lock the builder describes, owned by a new generated id. The calling thread waits
   * for the outcome, as {@link LockOperations.Builder#acquire()} does.
   *
   * @param builder the configured acquire
   * @return the handle, acquired or not; release it with {@link #release(LockHandle)}
   * @throws IllegalStateException if called on a Redisson I/O thread, for example inside a callback
   *     of a Redisson async call, before anything is sent to Redis
   * @throws RuntimeException any Redisson exception, for example when Redis is unreachable
   */
  public static @NonNull LockHandle acquire(@NonNull LockOperations.Builder builder) {
    return builder.acquire(ThreadLocalRandom.current().nextLong(Long.MIN_VALUE, 0));
  }

  /**
   * Releases a handle from {@link #acquire(LockOperations.Builder)} without blocking, on any
   * thread. A failed release is logged as a WARN, as {@link LockHandle#close()} logs it.
   *
   * @param handle the handle
   * @return a stage that completes when the release has finished; it never completes exceptionally
   */
  public static @NonNull CompletionStage<Void> release(@NonNull LockHandle handle) {
    return handle.releaseAsync();
  }
}
