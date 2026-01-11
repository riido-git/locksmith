package in.riido.locksmith.models;

import in.riido.locksmith.handler.SemaphoreSkipHandler;
import java.lang.reflect.Method;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

/**
 * Provides contextual information about a semaphore permit acquisition attempt.
 *
 * <p>This record is passed to {@link SemaphoreSkipHandler} implementations to provide all relevant
 * information about the failed permit acquisition, enabling custom handling logic.
 *
 * @param semaphoreKey the Redis semaphore key that could not acquire a permit
 * @param methodName the formatted method name (e.g., "MyService.processOrder")
 * @param method the method that was intercepted
 * @param args the arguments passed to the method
 * @param returnType the return type of the method
 * @author Garvit Joshi
 * @since 2.0.0
 */
public record SemaphoreContext(
    @NonNull String semaphoreKey,
    @NonNull String methodName,
    @NonNull Method method,
    @NonNull Object[] args,
    @NonNull Class<?> returnType) {

  /**
   * Compact constructor that validates all parameters are non-null.
   *
   * @param semaphoreKey the Redis semaphore key that could not acquire a permit
   * @param methodName the formatted method name (e.g., "MyService.processOrder")
   * @param method the method that was intercepted
   * @param args the arguments passed to the method
   * @param returnType the return type of the method
   */
  public SemaphoreContext {
    Objects.requireNonNull(semaphoreKey, "semaphoreKey must not be null");
    Objects.requireNonNull(methodName, "methodName must not be null");
    Objects.requireNonNull(method, "method must not be null");
    Objects.requireNonNull(args, "args must not be null");
    Objects.requireNonNull(returnType, "returnType must not be null");
  }
}
