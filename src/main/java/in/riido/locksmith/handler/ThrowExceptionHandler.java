package in.riido.locksmith.handler;

import in.riido.locksmith.exception.LockNotAcquiredException;

/**
 * A {@link LockSkipHandler} that throws {@link LockNotAcquiredException} when a lock cannot be
 * acquired. This is the default handler used by {@link in.riido.locksmith.DistributedLock}.
 *
 * @author Garvit Joshi
 * @since 1.2.0
 */
public class ThrowExceptionHandler implements LockSkipHandler {

  @Override
  public Object handle(LockContext context) {
    throw new LockNotAcquiredException(context.lockKey(), context.methodName());
  }
}
