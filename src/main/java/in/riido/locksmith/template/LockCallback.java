package in.riido.locksmith.template;

import org.jspecify.annotations.Nullable;

/**
 * Functional interface for code to be executed within a distributed lock.
 *
 * <p>This callback is used with {@link LocksmithLockTemplate#withKey} to execute code while holding
 * a distributed lock. The callback may throw any exception, which will be propagated to the caller
 * after the lock is released.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * String result = lockTemplate.withKey("my-key")
 *     .execute(() -> {
 *         // Code executed while holding the lock
 *         return "result";
 *     });
 * }</pre>
 *
 * @param <T> the type of result returned by the callback
 * @author Garvit Joshi
 * @since 2.1.0
 * @see LocksmithLockTemplate
 */
@FunctionalInterface
public interface LockCallback<T> {

  /**
   * Executes the callback logic while holding the distributed lock.
   *
   * @return the result of the execution, may be null
   * @throws Exception if any error occurs during execution
   */
  @Nullable T execute() throws Exception;
}
