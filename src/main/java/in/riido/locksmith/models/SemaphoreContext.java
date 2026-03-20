package in.riido.locksmith.models;

import in.riido.locksmith.handler.SemaphoreSkipHandler;
import java.lang.reflect.Method;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Provides contextual information about a semaphore permit acquisition attempt.
 *
 * <p>This record is passed to {@link SemaphoreSkipHandler} implementations to provide all relevant
 * information about the failed permit acquisition, enabling custom handling logic.
 *
 * <p><b>Thread Safety Note:</b> The {@code args} array is passed by reference from the aspect and
 * is guaranteed not to be modified by the Locksmith framework. Handlers may safely read the
 * arguments but should avoid modifying them to prevent unexpected side effects on the original
 * method invocation.
 *
 * @param semaphoreKey the Redis semaphore key that could not acquire a permit
 * @param methodName the formatted method name (e.g., "MyService.processOrder")
 * @param method the method that was intercepted
 * @param args the arguments passed to the method (read-only by convention; not modified by the
 *     aspect)
 * @param returnType the return type of the method
 * @param permitId the permit ID if one was acquired, null otherwise. When used in skip handlers,
 *     this is always null because skip handlers are only invoked when permit acquisition fails.
 * @author Garvit Joshi
 * @since 2.0.0
 */
public record SemaphoreContext(
    @NonNull String semaphoreKey,
    @NonNull String methodName,
    @NonNull Method method,
    @NonNull Object[] args,
    @NonNull Class<?> returnType,
    @Nullable String permitId) {

  /**
   * Compact constructor that validates all parameters are non-null (except permitId which is
   * nullable).
   *
   * @param semaphoreKey the Redis semaphore key that could not acquire a permit
   * @param methodName the formatted method name (e.g., "MyService.processOrder")
   * @param method the method that was intercepted
   * @param args the arguments passed to the method
   * @param returnType the return type of the method
   * @param permitId the permit ID if one was acquired, null otherwise
   */
  public SemaphoreContext {
    Objects.requireNonNull(semaphoreKey, "semaphoreKey must not be null");
    Objects.requireNonNull(methodName, "methodName must not be null");
    Objects.requireNonNull(method, "method must not be null");
    Objects.requireNonNull(args, "args must not be null");
    Objects.requireNonNull(returnType, "returnType must not be null");
  }
}
