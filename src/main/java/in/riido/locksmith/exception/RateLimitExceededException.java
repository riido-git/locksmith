package in.riido.locksmith.exception;

import java.io.Serial;
import org.jspecify.annotations.NonNull;

/**
 * Exception thrown when a rate limit is exceeded and no permit can be acquired.
 *
 * <p>This exception indicates that the rate limit for the specified key has been reached for the
 * current interval. Depending on the use case, the caller may choose to:
 *
 * <ul>
 *   <li>Retry the operation after a delay
 *   <li>Skip the operation entirely
 *   <li>Log and continue with alternative logic
 *   <li>Return a cached or default response
 * </ul>
 *
 * @author Garvit Joshi
 * @since 3.0.0
 */
public class RateLimitExceededException extends RuntimeException {

  @Serial private static final long serialVersionUID = -91156789012345680L;

  /** The Redis key of the rate limiter that exceeded its limit. */
  private final String rateLimitKey;

  /** The name of the method that was rate limited. */
  private final String methodName;

  /**
   * Constructs a new RateLimitExceededException.
   *
   * @param rateLimitKey the Redis key of the rate limiter that exceeded its limit
   * @param methodName the name of the method that was rate limited
   */
  public RateLimitExceededException(@NonNull String rateLimitKey, @NonNull String methodName) {
    super(
        String.format(
            "Rate limit exceeded for [%s] in method [%s]. " + "Try again later.",
            rateLimitKey, methodName));
    this.rateLimitKey = rateLimitKey;
    this.methodName = methodName;
  }

  /**
   * Returns the Redis key of the rate limiter that exceeded its limit.
   *
   * @return the rate limiter key
   */
  @NonNull
  public String getRateLimitKey() {
    return rateLimitKey;
  }

  /**
   * Returns the name of the method that was rate limited.
   *
   * @return the method name
   */
  @NonNull
  public String getMethodName() {
    return methodName;
  }
}
