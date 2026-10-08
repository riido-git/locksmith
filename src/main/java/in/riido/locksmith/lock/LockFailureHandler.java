package in.riido.locksmith.lock;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Decides what a {@code @DistributedLock} method returns when its lock is not acquired and its
 * failure policy is {@code HANDLER}. Implementations are Spring beans, resolved by type.
 */
@FunctionalInterface
public interface LockFailureHandler {

  /**
   * Handles a lock that was not acquired. Any exception thrown here propagates from the annotated
   * method.
   *
   * @param context the key, method, arguments and wait time of the failed call
   * @return the value the annotated method returns, as-is
   */
  @Nullable Object onFailure(@NonNull LockFailureContext context);
}
