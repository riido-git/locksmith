package in.riido.locksmith.template;

import org.jspecify.annotations.Nullable;

/**
 * Functional interface for code to be executed while holding a distributed semaphore permit.
 *
 * <p>This callback is used with {@link LocksmithSemaphoreTemplate#executeWithPermit} to execute
 * code while holding a semaphore permit. The callback may throw any exception, which will be
 * propagated to the caller after the permit is released.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * String result = semaphoreTemplate.executeWithPermit("my-key", 5, () -> {
 *     // Code executed while holding the permit
 *     return "result";
 * });
 * }</pre>
 *
 * @param <T> the type of result returned by the callback
 * @author Garvit Joshi
 * @since 2.1.0
 * @see LocksmithSemaphoreTemplate
 */
@FunctionalInterface
public interface SemaphoreCallback<T> {

  /**
   * Executes the callback logic while holding the semaphore permit.
   *
   * @return the result of the execution, may be null
   * @throws Exception if any error occurs during execution
   */
  @Nullable T execute() throws Exception;
}
