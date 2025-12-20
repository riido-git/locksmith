package in.riido.locksmith;

import in.riido.locksmith.exception.LockNotAcquiredException;
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
     * The lock acquisition mode. Determines behavior when the lock is already held.
     *
     * @return the acquisition mode, defaults to SKIP_IMMEDIATELY
     */
    LockAcquisitionMode mode() default LockAcquisitionMode.SKIP_IMMEDIATELY;

    /**
     * Override the default lease time in minutes. The lock will be automatically released after this
     * duration. Use -1 to use the default from configuration.
     *
     * @return lease time in minutes, -1 for default
     */
    long leaseTimeMinutes() default -1;

    /**
     * Override the default wait time in seconds for WAIT_AND_SKIP mode. Use -1 to use the default
     * from configuration.
     *
     * @return wait time in seconds, -1 for default
     */
    long waitTimeSeconds() default -1;

    /**
     * Defines the behavior when the lock cannot be acquired and method execution is skipped.
     *
     * @return the skip behavior, defaults to THROW_EXCEPTION
     */
    SkipBehavior onSkip() default SkipBehavior.THROW_EXCEPTION;
}
