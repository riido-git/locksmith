package in.riido.locksmith.models;

import in.riido.locksmith.handler.LockSkipHandler;
import java.lang.reflect.Method;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

/**
 * Provides contextual information about a lock acquisition attempt.
 *
 * <p>This record is passed to {@link LockSkipHandler} implementations to provide all relevant
 * information about the failed lock acquisition, enabling custom handling logic.
 *
 * <p><b>Thread Safety Note:</b> The {@code args} array is passed by reference from the aspect and
 * is guaranteed not to be modified by the Locksmith framework. Handlers may safely read the
 * arguments but should avoid modifying them to prevent unexpected side effects on the original
 * method invocation.
 *
 * @param lockKey the Redis lock key that could not be acquired
 * @param methodName the formatted method name (e.g., "MyService.processOrder")
 * @param method the method that was intercepted
 * @param args the arguments passed to the method (read-only by convention; not modified by the
 *     aspect)
 * @param returnType the return type of the method
 * @author Garvit Joshi
 * @since 1.2.0
 */
public record LockContext(
    @NonNull String lockKey,
    @NonNull String methodName,
    @NonNull Method method,
    @NonNull Object[] args,
    @NonNull Class<?> returnType) {

  /**
   * Compact constructor that validates all parameters are non-null.
   *
   * @param lockKey the Redis lock key that could not be acquired
   * @param methodName the formatted method name (e.g., "MyService.processOrder")
   * @param method the method that was intercepted
   * @param args the arguments passed to the method
   * @param returnType the return type of the method
   */
  public LockContext {
    Objects.requireNonNull(lockKey, "lockKey must not be null");
    Objects.requireNonNull(methodName, "methodName must not be null");
    Objects.requireNonNull(method, "method must not be null");
    Objects.requireNonNull(args, "args must not be null");
    Objects.requireNonNull(returnType, "returnType must not be null");
  }
}
