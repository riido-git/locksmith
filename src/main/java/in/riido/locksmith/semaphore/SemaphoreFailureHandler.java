package in.riido.locksmith.semaphore;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Decides what a {@code @DistributedSemaphore} method returns when its permit is not acquired and
 * its failure policy is {@code HANDLER}. Implementations are Spring beans, resolved by type.
 */
@FunctionalInterface
public interface SemaphoreFailureHandler {

  /**
   * Handles a permit that was not acquired. Any exception thrown here propagates from the annotated
   * method.
   *
   * @param context the key, permit count, method, arguments and wait time of the failed call
   * @return the value the annotated method returns, as-is
   */
  @Nullable Object onFailure(@NonNull SemaphoreFailureContext context);
}
