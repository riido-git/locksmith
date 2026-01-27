package in.riido.locksmith.handler;

import in.riido.locksmith.models.RateLimitContext;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Interface for handling rate limit exceeded scenarios with custom logic.
 *
 * <p>Implementations of this interface are invoked when a rate limit is exceeded and the method
 * execution is skipped. This enables custom behavior such as:
 *
 * <ul>
 *   <li>Logging to specific systems
 *   <li>Sending alerts or notifications
 *   <li>Returning specialized fallback values
 *   <li>Executing alternative processing logic
 * </ul>
 *
 * <p><b>Handler Resolution:</b> Handlers are resolved in the following order:
 *
 * <ol>
 *   <li>Look up as a Spring bean from ApplicationContext by type
 *   <li>Fall back to reflection-based instantiation (requires public no-arg constructor)
 * </ol>
 *
 * <p><b>Thread-Safety Requirement:</b> Implementations must be stateless and thread-safe. Handler
 * instances are cached and reused across all rate limit breaches. The same handler instance may be
 * invoked concurrently by multiple threads. Do not use instance variables to store state between
 * invocations.
 *
 * <p>Example Spring bean implementation with dependency injection:
 *
 * <pre>{@code
 * @Component
 * public class AlertingRateLimitHandler implements RateLimitSkipHandler {
 *     private final AlertService alertService;
 *
 *     public AlertingRateLimitHandler(AlertService alertService) {
 *         this.alertService = alertService;
 *     }
 *
 *     @Override
 *     public Object handle(RateLimitContext context) {
 *         alertService.sendAlert("Rate limit exceeded for: " + context.rateLimitKey());
 *         return null; // or return a fallback value
 *     }
 * }
 * }</pre>
 *
 * <p>Example simple implementation (no Spring dependencies):
 *
 * <pre>{@code
 * public class LoggingRateLimitHandler implements RateLimitSkipHandler {
 *
 *     @Override
 *     public Object handle(RateLimitContext context) {
 *         System.out.println("Rate limit exceeded for: " + context.rateLimitKey());
 *         return null;
 *     }
 * }
 * }</pre>
 *
 * <p>Usage:
 *
 * <pre>{@code
 * @RateLimit(key = "api-call", permits = 100, interval = "1m",
 *     skipHandler = AlertingRateLimitHandler.class)
 * public void myApiCall() { }
 * }</pre>
 *
 * @author Garvit Joshi
 * @see RateLimitContext
 * @since 3.0.0
 */
public interface RateLimitSkipHandler {

  /**
   * Handles the case when a rate limit is exceeded.
   *
   * <p>This method is called when the rate limit is exceeded and the method execution is skipped.
   * The returned value will be used as the method's return value.
   *
   * <p><b>Important:</b> This method must be thread-safe as it may be called concurrently by
   * multiple threads on the same handler instance.
   *
   * @param context the rate limit context containing information about the exceeded limit
   * @return the value to return from the method, must be compatible with the method's return type
   * @throws RuntimeException implementations may throw exceptions to indicate failure
   */
  @Nullable Object handle(@NonNull RateLimitContext context);
}
