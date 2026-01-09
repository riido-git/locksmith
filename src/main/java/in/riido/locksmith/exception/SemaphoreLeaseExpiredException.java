package in.riido.locksmith.exception;

import java.io.Serial;
import org.jspecify.annotations.NonNull;

/**
 * Exception thrown when a method's execution time exceeds the configured semaphore permit lease
 * duration.
 *
 * <p>This exception is thrown after the method completes when the configured behavior is {@link
 * in.riido.locksmith.LeaseExpirationBehavior#THROW_EXCEPTION}. It indicates that the permit may
 * have expired during execution, potentially allowing more concurrent executions than intended.
 *
 * @author Garvit Joshi
 * @since 2.0.0
 */
public class SemaphoreLeaseExpiredException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /** The Redis key of the semaphore whose permit expired. */
  private final String semaphoreKey;

  /** The name of the method that exceeded lease time. */
  private final String methodName;

  /** The configured lease time in milliseconds. */
  private final long leaseTimeMs;

  /** The actual execution time in milliseconds. */
  private final long executionTimeMs;

  /**
   * Constructs a new SemaphoreLeaseExpiredException.
   *
   * @param semaphoreKey the semaphore key whose permit expired
   * @param methodName the method that exceeded lease time
   * @param leaseTimeMs the configured lease time in milliseconds
   * @param executionTimeMs the actual execution time in milliseconds
   */
  public SemaphoreLeaseExpiredException(
      @NonNull String semaphoreKey,
      @NonNull String methodName,
      long leaseTimeMs,
      long executionTimeMs) {
    super(
        String.format(
            "Semaphore [%s] permit lease expired during execution of [%s]. "
                + "Lease time: %dms, Execution time: %dms. "
                + "The permit may have been acquired by another instance during execution.",
            semaphoreKey, methodName, leaseTimeMs, executionTimeMs));
    this.semaphoreKey = semaphoreKey;
    this.methodName = methodName;
    this.leaseTimeMs = leaseTimeMs;
    this.executionTimeMs = executionTimeMs;
  }

  /**
   * Returns the semaphore key whose permit expired.
   *
   * @return the semaphore key
   */
  @NonNull
  public String getSemaphoreKey() {
    return semaphoreKey;
  }

  /**
   * Returns the method name that exceeded lease time.
   *
   * @return the method name
   */
  @NonNull
  public String getMethodName() {
    return methodName;
  }

  /**
   * Returns the configured lease time in milliseconds.
   *
   * @return the lease time in milliseconds
   */
  public long getLeaseTimeMs() {
    return leaseTimeMs;
  }

  /**
   * Returns the actual execution time in milliseconds.
   *
   * @return the execution time in milliseconds
   */
  public long getExecutionTimeMs() {
    return executionTimeMs;
  }
}
