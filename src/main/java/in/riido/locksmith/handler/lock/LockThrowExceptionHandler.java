package in.riido.locksmith.handler.lock;

import in.riido.locksmith.exception.LockNotAcquiredException;
import in.riido.locksmith.handler.LockSkipHandler;
import in.riido.locksmith.models.LockContext;
import org.jspecify.annotations.NonNull;

/**
 * A {@link LockSkipHandler} that throws {@link LockNotAcquiredException} when a lock cannot be
 * acquired. This is the default handler used by {@link in.riido.locksmith.DistributedLock}.
 *
 * @author Garvit Joshi
 * @since 1.2.0
 */
public class LockThrowExceptionHandler implements LockSkipHandler {

  /** Default constructor. */
  public LockThrowExceptionHandler() {}

  @Override
  public Object handle(@NonNull LockContext context) {
    throw new LockNotAcquiredException(context.lockKey(), context.methodName());
  }
}
