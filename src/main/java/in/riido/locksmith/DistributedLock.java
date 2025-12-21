package in.riido.locksmith;

import in.riido.locksmith.exception.LockNotAcquiredException;
import in.riido.locksmith.handler.LockSkipHandler;
import in.riido.locksmith.handler.ThrowExceptionHandler;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to apply distributed locking on a method. Only one instance across all servers can
 * execute the annotated method at a time for the given lock key.
 *
 * <p>When the lock cannot be acquired, the behavior is controlled by {@link #onSkip()}:
 *
 * <ul>
 *   <li>{@link SkipBehavior#THROW_EXCEPTION} (default): Throws {@link LockNotAcquiredException}
 *   <li>{@link SkipBehavior#RETURN_DEFAULT}: Returns null for objects, default values for
 *       primitives
 * </ul>
 *
 * <p>Usage examples:
 *
 * <pre>{@code
 * // Static key - throws exception if lock not acquired
 * @DistributedLock(key = "critical-task")
 * public void criticalTask() { }
 *
 * // For scheduled tasks - silently skip if lock not acquired
 * @DistributedLock(key = "scheduled-task", onSkip = SkipBehavior.RETURN_DEFAULT)
 * public void scheduledTask() { }
 *
 * // SpEL with method parameter - lock per user
 * @DistributedLock(key = "#userId")
 * public void processUser(String userId) { }
 *
 * // SpEL with object property
 * @DistributedLock(key = "#user.id")
 * public void updateUser(User user) { }
 *
 * // SpEL with concatenation
 * @DistributedLock(key = "'user-' + #userId")
 * public void processUser(Long userId) { }
 *
 * // Read lock - allows concurrent reads
 * @DistributedLock(key = "resource", type = LockType.READ)
 * public Data readData() { }
 *
 * // Write lock - exclusive access for writes
 * @DistributedLock(key = "resource", type = LockType.WRITE)
 * public void writeData(Data data) { }
 * }</pre>
 *
 * @author Garvit Joshi
 * @since 1.0.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DistributedLock {

  /**
   * The unique key for the lock. This key is used to identify the lock in Redis. Different tasks
   * should use different keys.
   *
   * <p>Supports Spring Expression Language (SpEL). Use {@code #paramName} to reference method
   * parameters, {@code #paramName.property} to access object properties.
   *
   * @return the lock key (literal or SpEL expression)
   */
  String key();

  /**
   * The type of lock to acquire.
   *
   * <ul>
   *   <li>{@link LockType#REENTRANT} (default): Exclusive lock, only one holder at a time
   *   <li>{@link LockType#READ}: Shared lock, multiple concurrent readers allowed
   *   <li>{@link LockType#WRITE}: Exclusive lock, no readers or writers allowed simultaneously
   * </ul>
   *
   * <p>When using READ or WRITE locks, all methods accessing the same resource should use the same
   * lock key to ensure proper synchronization.
   *
   * @return the lock type, defaults to REENTRANT
   */
  LockType type() default LockType.REENTRANT;

  /**
   * The lock acquisition mode. Determines behavior when the lock is already held.
   *
   * @return the acquisition mode, defaults to SKIP_IMMEDIATELY
   */
  LockAcquisitionMode mode() default LockAcquisitionMode.SKIP_IMMEDIATELY;

  /**
   * Override the default lease time. The lock will be automatically released after this duration.
   * Use an empty string to use the default from configuration.
   *
   * <p>Accepts duration strings in the following formats:
   *
   * <ul>
   *   <li>Simple format: "10m" (10 minutes), "30s" (30 seconds), "1h" (1 hour)
   *   <li>ISO-8601 format: "PT10M" (10 minutes), "PT30S" (30 seconds)
   * </ul>
   *
   * @return lease time duration string, empty for default
   */
  String leaseTime() default "";

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
   * Defines the behavior when the lock cannot be acquired and method execution is skipped.
   *
   * <p>This is ignored if {@link #skipHandler()} is set to a custom handler.
   *
   * @return the skip behavior, defaults to THROW_EXCEPTION
   * @see #skipHandler()
   */
  SkipBehavior onSkip() default SkipBehavior.THROW_EXCEPTION;

  /**
   * Custom handler for lock acquisition failures.
   *
   * <p>The handler must have a public no-argument constructor. Built-in handlers:
   *
   * <ul>
   *   <li>{@link ThrowExceptionHandler} (default): Throws {@link LockNotAcquiredException}
   *   <li>{@link in.riido.locksmith.handler.ReturnDefaultHandler}: Returns null/default values
   * </ul>
   *
   * <p>Example custom handler:
   *
   * <pre>{@code
   * public class MyCustomHandler implements LockSkipHandler {
   *     @Override
   *     public Object handle(LockContext context) {
   *         log.warn("Lock {} not acquired for {}", context.lockKey(), context.methodName());
   *         return null;
   *     }
   * }
   *
   * @DistributedLock(key = "my-task", skipHandler = MyCustomHandler.class)
   * public void myTask() { }
   * }</pre>
   *
   * @return the skip handler class, defaults to ThrowExceptionHandler
   * @see LockSkipHandler
   */
  Class<? extends LockSkipHandler> skipHandler() default ThrowExceptionHandler.class;

  /**
   * Defines the behavior when method execution time exceeds the configured lease duration.
   *
   * <p>When a method runs longer than its lock's lease time, the lock may expire while the method
   * is still executing. This can lead to concurrent access by other instances. This parameter
   * configures how to handle detection of such scenarios after method completion.
   *
   * <ul>
   *   <li>{@link LeaseExpirationBehavior#LOG_WARNING} (default): Log a warning message
   *   <li>{@link LeaseExpirationBehavior#THROW_EXCEPTION}: Throw {@link
   *       in.riido.locksmith.exception.LeaseExpiredException}
   *   <li>{@link LeaseExpirationBehavior#IGNORE}: Silently ignore
   * </ul>
   *
   * @return the lease expiration behavior, defaults to LOG_WARNING
   */
  LeaseExpirationBehavior onLeaseExpired() default LeaseExpirationBehavior.LOG_WARNING;
}
