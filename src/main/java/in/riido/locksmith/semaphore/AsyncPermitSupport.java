package in.riido.locksmith.semaphore;

import java.util.concurrent.CompletionStage;
import org.jspecify.annotations.NonNull;

/**
 * Internal API. Not for use by adopters. May change without notice.
 *
 * <p>Releases permits of annotated methods that return a {@code CompletionStage}. Such a permit is
 * released when the stage completes, possibly on a Redisson I/O thread, where a blocking release is
 * refused, so the release does not block.
 */
public final class AsyncPermitSupport {

  private AsyncPermitSupport() {}

  /**
   * Releases a permit without blocking, on any thread. A failed release is logged as a WARN, as
   * {@link PermitHandle#close()} logs it.
   *
   * @param handle the handle
   * @return a stage that completes when the release has finished; it never completes exceptionally
   */
  public static @NonNull CompletionStage<Void> release(@NonNull PermitHandle handle) {
    return handle.releaseAsync();
  }
}
