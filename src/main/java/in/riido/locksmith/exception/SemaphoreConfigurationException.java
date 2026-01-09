package in.riido.locksmith.exception;

import java.io.Serial;
import org.jspecify.annotations.NonNull;

/**
 * Exception thrown when a semaphore configuration is invalid or inconsistent.
 *
 * <p>This exception is thrown in the following scenarios:
 *
 * <ul>
 *   <li>The same semaphore key is used with different permits values in the same codebase
 *   <li>The permits value is not a positive integer
 *   <li>Other configuration validation failures
 * </ul>
 *
 * @author Garvit Joshi
 * @since 2.0.0
 */
public class SemaphoreConfigurationException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /** The semaphore key with configuration issues. */
  private final String semaphoreKey;

  /**
   * Constructs a new SemaphoreConfigurationException.
   *
   * @param message the detail message
   * @param semaphoreKey the semaphore key with configuration issues
   */
  public SemaphoreConfigurationException(@NonNull String message, @NonNull String semaphoreKey) {
    super(message);
    this.semaphoreKey = semaphoreKey;
  }

  /**
   * Constructs a new SemaphoreConfigurationException with a cause.
   *
   * @param message the detail message
   * @param semaphoreKey the semaphore key with configuration issues
   * @param cause the cause of this exception
   */
  public SemaphoreConfigurationException(
      @NonNull String message, @NonNull String semaphoreKey, @NonNull Throwable cause) {
    super(message, cause);
    this.semaphoreKey = semaphoreKey;
  }

  /**
   * Returns the semaphore key with configuration issues.
   *
   * @return the semaphore key
   */
  @NonNull
  public String getSemaphoreKey() {
    return semaphoreKey;
  }
}
