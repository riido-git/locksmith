package in.riido.locksmith.template;

import org.jspecify.annotations.Nullable;

/**
 * Functional interface for code to be executed within a rate limit.
 *
 * <p>This callback is used with {@link LocksmithRateLimitTemplate#executeWithRateLimit} to execute
 * code after acquiring a rate limit permit. The callback may throw any exception, which will be
 * propagated to the caller.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * String result = rateLimitTemplate.executeWithRateLimit("api-call", () -> {
 *     // Code executed after permit acquisition
 *     return apiClient.call();
 * });
 * }</pre>
 *
 * @param <T> the type of result returned by the callback
 * @author Garvit Joshi
 * @since 3.0.0
 * @see LocksmithRateLimitTemplate
 */
@FunctionalInterface
public interface RateLimitCallback<T> {

  /**
   * Executes the callback logic after rate limit permit acquisition.
   *
   * @return the result of the execution, may be null
   * @throws Exception if any error occurs during execution
   */
  @Nullable T execute() throws Exception;
}
