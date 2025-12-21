package in.riido.locksmith.exception;

/**
 * Exception thrown when a method's execution time exceeds the configured lease duration.
 *
 * <p>This exception is thrown after the method completes when the configured behavior is {@link
 * in.riido.locksmith.LeaseExpirationBehavior#THROW_EXCEPTION}. It indicates that the lock may have
 * expired during execution, potentially allowing concurrent access by other instances.
 *
 * @author Garvit Joshi
 * @since 1.2.0
 */
public class LeaseExpiredException extends RuntimeException {

  @java.io.Serial private static final long serialVersionUID = 1L;

  /** The Redis key of the lock that expired. */
  private final String lockKey;

  /** The name of the method that exceeded lease time. */
  private final String methodName;

  /** The configured lease time in milliseconds. */
  private final long leaseTimeMs;

  /** The actual execution time in milliseconds. */
  private final long executionTimeMs;

  /**
   * Constructs a new LeaseExpiredException.
   *
   * @param lockKey the lock key that expired
   * @param methodName the method that exceeded lease time
   * @param leaseTimeMs the configured lease time in milliseconds
   * @param executionTimeMs the actual execution time in milliseconds
   */
  public LeaseExpiredException(
      String lockKey, String methodName, long leaseTimeMs, long executionTimeMs) {
    super(
        String.format(
            "Lock [%s] lease expired during execution of [%s]. "
                + "Lease time: %dms, Execution time: %dms. "
                + "The lock may have been acquired by another instance during execution.",
            lockKey, methodName, leaseTimeMs, executionTimeMs));
    this.lockKey = lockKey;
    this.methodName = methodName;
    this.leaseTimeMs = leaseTimeMs;
    this.executionTimeMs = executionTimeMs;
  }

  /**
   * Returns the lock key that expired.
   *
   * @return the lock key
   */
  public String getLockKey() {
    return lockKey;
  }

  /**
   * Returns the method name that exceeded lease time.
   *
   * @return the method name
   */
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
