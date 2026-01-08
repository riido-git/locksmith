package in.riido.locksmith.handler;

import in.riido.locksmith.exception.SemaphoreNotAcquiredException;

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
  public Object handle(SemaphoreContext context) {
    throw new SemaphoreNotAcquiredException(context.semaphoreKey(), context.methodName());
  }
}
