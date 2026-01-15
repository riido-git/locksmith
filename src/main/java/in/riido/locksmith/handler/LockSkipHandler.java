package in.riido.locksmith.handler;

import in.riido.locksmith.models.LockContext;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Interface for handling lock acquisition failures with custom logic.
 *
 * <p>Implementations of this interface are invoked when a distributed lock cannot be acquired and
 * the method execution is skipped. This enables custom behavior such as:
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
 * instances are cached and reused across all lock acquisition failures. The same handler instance
 * may be invoked concurrently by multiple threads. Do not use instance variables to store state
 * between invocations.
 *
 * <p>Example Spring bean implementation with dependency injection:
 *
 * <pre>{@code
 * @Component
 * public class AlertingSkipHandler implements LockSkipHandler {
 *     private final AlertService alertService;
 *
 *     public AlertingSkipHandler(AlertService alertService) {
 *         this.alertService = alertService;
 *     }
 *
 *     @Override
 *     public Object handle(LockContext context) {
 *         alertService.sendAlert("Lock acquisition failed for: " + context.lockKey());
 *         return null; // or return a fallback value
 *     }
 * }
 * }</pre>
 *
 * <p>Example simple implementation (no Spring dependencies):
 *
 * <pre>{@code
 * public class LoggingSkipHandler implements LockSkipHandler {
 *
 *     @Override
 *     public Object handle(LockContext context) {
 *         System.out.println("Lock acquisition failed for: " + context.lockKey());
 *         return null;
 *     }
 * }
 * }</pre>
 *
 * <p>Usage:
 *
 * <pre>{@code
 * @DistributedLock(key = "my-task", skipHandler = AlertingSkipHandler.class)
 * public void myTask() { }
 * }</pre>
 *
 * @author Garvit Joshi
 * @see LockContext
 * @since 1.2.0
 */
public interface LockSkipHandler {

  /**
   * Handles the case when a lock cannot be acquired.
   *
   * <p>This method is called when lock acquisition fails and the method execution is skipped. The
   * returned value will be used as the method's return value.
   *
   * <p><b>Important:</b> This method must be thread-safe as it may be called concurrently by
   * multiple threads on the same handler instance.
   *
   * @param context the lock context containing information about the failed acquisition
   * @return the value to return from the method, must be compatible with the method's return type
   * @throws RuntimeException implementations may throw exceptions to indicate failure
   */
  @Nullable Object handle(@NonNull LockContext context);
}
