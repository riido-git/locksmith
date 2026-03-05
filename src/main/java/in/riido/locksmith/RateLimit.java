package in.riido.locksmith;

import in.riido.locksmith.exception.RateLimitExceededException;
import in.riido.locksmith.handler.RateLimitSkipHandler;
import in.riido.locksmith.handler.ratelimit.RateLimitReturnDefaultHandler;
import in.riido.locksmith.handler.ratelimit.RateLimitThrowExceptionHandler;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.redisson.api.RateType;

/**
 * Annotation to apply distributed rate limiting on a method. Limits the number of executions within
 * a specified time interval across all servers.
 *
 * <p>Unlike {@link DistributedSemaphore} which limits concurrent executions, rate limiting controls
 * the throughput over time. This is useful for:
 *
 * <ul>
 *   <li>API rate limiting per user/client
 *   <li>Throttling background job execution
 *   <li>Preventing resource exhaustion
 * </ul>
 *
 * <p>When the rate limit is exceeded, the behavior is controlled by {@link #skipHandler()}:
 *
 * <ul>
 *   <li>{@link RateLimitThrowExceptionHandler} (default): Throws {@link RateLimitExceededException}
 *   <li>{@link RateLimitReturnDefaultHandler}: Returns null for objects, default values for
 *       primitives
 * </ul>
 *
 * <p>Usage examples:
 *
 * <pre>{@code
 * // Basic: 10 requests per second (defaults)
 * @RateLimit(key = "api-call")
 * public void apiCall() { }
 *
 * // Custom rate: 100 requests per minute
 * @RateLimit(key = "heavy-operation", permits = 100, interval = "1m")
 * public void heavyOperation() { }
 *
 * // Per-user rate limiting with SpEL
 * @RateLimit(key = "#{#userId}", permits = 5, interval = "1s")
 * public void userAction(String userId) { }
 *
 * // Wait for permit instead of immediate rejection
 * @RateLimit(key = "throttled", mode = AcquisitionMode.WAIT_AND_SKIP, waitTime = "5s")
 * public void throttledOperation() { }
 *
 * // Per-client instance rate limiting
 * @RateLimit(key = "local-api", permits = 20, type = RateType.PER_CLIENT)
 * public void localApiCall() { }
 *
 * // Return default value on rate limit exceeded
 * @RateLimit(key = "api", skipHandler = RateLimitReturnDefaultHandler.class)
 * public String getData() { return "data"; }
 * }</pre>
 *
 * <p><b>Important:</b> This annotation uses Redisson's {@code RRateLimiter} which provides
 * distributed rate limiting. The rate limiter automatically replenishes permits based on the
 * configured interval.
 *
 * @author Garvit Joshi
 * @since 3.0.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimit {

  /**
   * The unique key for the rate limiter. This key is used to identify the rate limiter in Redis.
   * Different resources should use different keys.
   *
   * <p>Supports Spring Expression Language (SpEL). SpEL expressions must be wrapped in {@code
   * #{...}} syntax. Use {@code #{#paramName}} to reference method parameters, {@code
   * #{#paramName.property}} to access object properties.
   *
   * <p>Literal keys (without {@code #{...}}) are used as-is and can contain any characters
   * including {@code #}.
   *
   * @return the rate limiter key (literal or SpEL expression)
   */
  String key();

  /**
   * The number of permits allowed in the interval.
   *
   * <p>This value is set when the rate limiter is first created in Redis. If the rate limiter
   * already exists with a different configuration, a warning is logged and the existing value is
   * used.
   *
   * <p>Must be a positive value (greater than 0).
   *
   * @return the number of permits per interval, defaults to 10
   */
  long permits() default 10;

  /**
   * The time interval for rate limiting. Permits are replenished after each interval.
   *
   * <p>Accepts duration strings in the following formats:
   *
   * <ul>
   *   <li>Simple format: "1s" (1 second), "10s" (10 seconds), "1m" (1 minute), "1h" (1 hour)
   *   <li>ISO-8601 format: "PT1S" (1 second), "PT10S" (10 seconds), "PT1M" (1 minute)
   * </ul>
   *
   * @return interval duration string, defaults to "1s" (permits per second)
   */
  String interval() default "1s";

  /**
   * Rate limit type. Determines how permits are shared.
   *
   * <ul>
   *   <li>{@link RateType#OVERALL}: Permits are shared across all clients/instances
   *   <li>{@link RateType#PER_CLIENT}: Each Redisson client instance gets its own rate limit quota
   * </ul>
   *
   * @return the rate type, defaults to OVERALL
   */
  RateType type() default RateType.OVERALL;

  /**
   * The permit acquisition mode. Determines behavior when no permit is available.
   *
   * <ul>
   *   <li>{@link AcquisitionMode#SKIP_IMMEDIATELY}: Return immediately if no permit available
   *   <li>{@link AcquisitionMode#WAIT_AND_SKIP}: Wait up to {@link #waitTime()} for a permit
   * </ul>
   *
   * @return the acquisition mode, defaults to SKIP_IMMEDIATELY
   */
  AcquisitionMode mode() default AcquisitionMode.SKIP_IMMEDIATELY;

  /**
   * Override the default wait time for WAIT_AND_SKIP mode. Use an empty string to use the default
   * from configuration.
   *
   * <p>Accepts duration strings in the following formats:
   *
   * <ul>
   *   <li>Simple format: "10s" (10 seconds), "5m" (5 minutes), "1h" (1 hour)
   *   <li>ISO-8601 format: "PT10S" (10 seconds), "PT5M" (5 minutes)
   * </ul>
   *
   * @return wait time duration string, empty for default
   */
  String waitTime() default "";

  /**
   * Custom handler for rate limit exceeded scenarios.
   *
   * <p>The handler must have a public no-argument constructor or be defined as a Spring bean.
   * Built-in handlers:
   *
   * <ul>
   *   <li>{@link RateLimitThrowExceptionHandler} (default): Throws {@link
   *       RateLimitExceededException}
   *   <li>{@link RateLimitReturnDefaultHandler}: Returns null/default values
   * </ul>
   *
   * @return the skip handler class, defaults to RateLimitThrowExceptionHandler
   * @see RateLimitSkipHandler
   */
  Class<? extends RateLimitSkipHandler> skipHandler() default RateLimitThrowExceptionHandler.class;
}
