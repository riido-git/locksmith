package in.riido.locksmith.exception;

import java.io.Serial;

/**
 * Exception thrown when a distributed lock cannot be acquired within the configured time.
 *
 * <p>This exception indicates that another server instance is currently holding the lock for the
 * requested resource. Depending on the use case, the caller may choose to:
 *
 * <ul>
 *   <li>Retry the operation after a delay
 *   <li>Skip the operation entirely
 *   <li>Log and continue with alternative logic
 * </ul>
 *
 * @author Garvit Joshi
 * @since 1.0.0
 */
public class LockNotAcquiredException extends RuntimeException {

  @Serial private static final long serialVersionUID = -6494677132062171394L;

  private final String lockKey;
  private final String methodName;

  /**
   * Constructs a new LockNotAcquiredException.
   *
   * @param lockKey the Redis key of the lock that could not be acquired
   * @param methodName the name of the method that required the lock
   */
  public LockNotAcquiredException(String lockKey, String methodName) {
    super(
        String.format(
            "Failed to acquire distributed lock [%s] for method [%s]. "
                + "Another instance is currently executing this task.",
            lockKey, methodName));
    this.lockKey = lockKey;
    this.methodName = methodName;
  }

  /**
   * Returns the Redis key of the lock that could not be acquired.
   *
   * @return the lock key
   */
  public String getLockKey() {
    return lockKey;
  }

  /**
   * Returns the name of the method that required the lock.
   *
   * @return the method name
   */
  public String getMethodName() {
    return methodName;
  }
}
