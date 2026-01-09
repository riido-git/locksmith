package in.riido.locksmith.exception;

import java.io.Serial;
import org.jspecify.annotations.NonNull;

/**
 * Exception thrown when a distributed semaphore permit cannot be acquired within the configured
 * time.
 *
 * <p>This exception indicates that all permits for the semaphore are currently held by other server
 * instances. Depending on the use case, the caller may choose to:
 *
 * <ul>
 *   <li>Retry the operation after a delay
 *   <li>Skip the operation entirely
 *   <li>Log and continue with alternative logic
 * </ul>
 *
 * @author Garvit Joshi
 * @since 2.0.0
 */
public class SemaphoreNotAcquiredException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /** The Redis key of the semaphore that could not acquire a permit. */
  private final String semaphoreKey;

  /** The name of the method that required the permit. */
  private final String methodName;

  /**
   * Constructs a new SemaphoreNotAcquiredException.
   *
   * @param semaphoreKey the Redis key of the semaphore that could not acquire a permit
   * @param methodName the name of the method that required the permit
   */
  public SemaphoreNotAcquiredException(@NonNull String semaphoreKey, @NonNull String methodName) {
    super(
        String.format(
            "Failed to acquire permit from semaphore [%s] for method [%s]. "
                + "All permits are currently held by other instances.",
            semaphoreKey, methodName));
    this.semaphoreKey = semaphoreKey;
    this.methodName = methodName;
  }

  /**
   * Returns the Redis key of the semaphore that could not acquire a permit.
   *
   * @return the semaphore key
   */
  @NonNull
  public String getSemaphoreKey() {
    return semaphoreKey;
  }

  /**
   * Returns the name of the method that required the permit.
   *
   * @return the method name
   */
  @NonNull
  public String getMethodName() {
    return methodName;
  }
}
