package in.riido.locksmith.handler;

/**
 * Interface for handling semaphore permit acquisition failures with custom logic.
 *
 * <p>Implementations of this interface are invoked when a distributed semaphore permit cannot be
 * acquired and the method execution is skipped. This enables custom behavior such as:
 *
 * <ul>
 *   <li>Logging to specific systems
 *   <li>Sending alerts or notifications
 *   <li>Returning specialized fallback values
 *   <li>Executing alternative processing logic
 * </ul>
 *
 * <p>Implementations must have a public no-argument constructor to be instantiated by the aspect.
 *
 * <p><b>Thread-Safety Requirement:</b> Implementations must be stateless and thread-safe. Handler
 * instances are cached and reused across all permit acquisition failures. The same handler instance
 * may be invoked concurrently by multiple threads. Do not use instance variables to store state
 * between invocations.
 *
 * <p>Example implementation:
 *
 * <pre>{@code
 * public class AlertingSemaphoreHandler implements SemaphoreSkipHandler {
 *
 *     @Override
 *     public Object handle(SemaphoreContext context) {
 *         alertService.sendAlert("Permit acquisition failed for: " + context.semaphoreKey());
 *         return null; // or return a fallback value
 *     }
 * }
 * }</pre>
 *
 * <p>Usage:
 *
 * <pre>{@code
 * @DistributedSemaphore(key = "my-pool", permits = 10, leaseTime = "5m",
 *     skipHandler = AlertingSemaphoreHandler.class)
 * public void myTask() { }
 * }</pre>
 *
 * @author Garvit Joshi
 * @see SemaphoreContext
 * @since 2.0.0
 */
public interface SemaphoreSkipHandler {

  /**
   * Handles the case when a semaphore permit cannot be acquired.
   *
   * <p>This method is called when permit acquisition fails and the method execution is skipped. The
   * returned value will be used as the method's return value.
   *
   * <p><b>Important:</b> This method must be thread-safe as it may be called concurrently by
   * multiple threads on the same handler instance.
   *
   * @param context the semaphore context containing information about the failed acquisition
   * @return the value to return from the method, must be compatible with the method's return type
   * @throws RuntimeException implementations may throw exceptions to indicate failure
   */
  Object handle(SemaphoreContext context);
}
