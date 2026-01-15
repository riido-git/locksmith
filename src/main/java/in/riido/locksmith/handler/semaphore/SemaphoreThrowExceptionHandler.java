package in.riido.locksmith.handler.semaphore;

import in.riido.locksmith.exception.SemaphoreNotAcquiredException;
import in.riido.locksmith.handler.SemaphoreSkipHandler;
import in.riido.locksmith.models.SemaphoreContext;
import org.jspecify.annotations.NonNull;

/**
 * A {@link SemaphoreSkipHandler} that throws {@link SemaphoreNotAcquiredException} when a semaphore
 * permit cannot be acquired. This is the default handler used by {@link
 * in.riido.locksmith.DistributedSemaphore}.
 *
 * @author Garvit Joshi
 * @since 2.0.0
 */
public class SemaphoreThrowExceptionHandler implements SemaphoreSkipHandler {

  /** Default constructor. */
  public SemaphoreThrowExceptionHandler() {}

  @Override
  public Object handle(@NonNull SemaphoreContext context) {
    throw new SemaphoreNotAcquiredException(context.semaphoreKey(), context.methodName());
  }
}
