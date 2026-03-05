package in.riido.locksmith.exception;

import java.io.Serial;
import org.jspecify.annotations.NonNull;

/**
 * Exception thrown when a rate limiter configuration is invalid or inconsistent.
 *
 * <p>This exception is thrown in the following scenarios:
 *
 * <ul>
 *   <li>The permits value is not a positive integer
 *   <li>The interval is zero or negative
 *   <li>Other configuration validation failures
 * </ul>
 *
 * @author Garvit Joshi
 * @since 3.0.0
 */
public class RateLimitConfigurationException extends RuntimeException {

  @Serial private static final long serialVersionUID = -8372649105423781640L;

  /** The rate limiter key with configuration issues. */
  private final String rateLimitKey;

  /**
   * Constructs a new RateLimitConfigurationException.
   *
   * @param message the detail message
   * @param rateLimitKey the rate limiter key with configuration issues
   */
  public RateLimitConfigurationException(@NonNull String message, @NonNull String rateLimitKey) {
    super(message);
    this.rateLimitKey = rateLimitKey;
  }

  /**
   * Constructs a new RateLimitConfigurationException with a cause.
   *
   * @param message the detail message
   * @param rateLimitKey the rate limiter key with configuration issues
   * @param cause the cause of this exception
   */
  public RateLimitConfigurationException(
      @NonNull String message, @NonNull String rateLimitKey, @NonNull Throwable cause) {
    super(message, cause);
    this.rateLimitKey = rateLimitKey;
  }

  /**
   * Returns the rate limiter key with configuration issues.
   *
   * @return the rate limiter key
   */
  @NonNull
  public String getRateLimitKey() {
    return rateLimitKey;
  }
}
