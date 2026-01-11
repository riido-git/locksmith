package in.riido.locksmith;

import in.riido.locksmith.exception.SemaphoreNotAcquiredException;
import in.riido.locksmith.handler.SemaphoreSkipHandler;
import in.riido.locksmith.handler.semaphore.SemaphoreReturnDefaultHandler;
import in.riido.locksmith.handler.semaphore.SemaphoreThrowExceptionHandler;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to apply distributed semaphore-based concurrency control on a method. Allows up to N
 * concurrent executions across all servers for the given semaphore key.
 *
 * <p>Unlike {@link DistributedLock} which allows only one execution at a time, a semaphore allows
 * multiple concurrent executions up to the configured {@link #permits()} limit. This is useful for:
 *
 * <ul>
 *   <li>Connection pool limiting
 *   <li>Batch processing with max parallelism
 *   <li>Resource throttling
 * </ul>
 *
 * <p>When a permit cannot be acquired, the behavior is controlled by {@link #skipHandler()}:
 *
 * <ul>
 *   <li>{@link SemaphoreThrowExceptionHandler} (default): Throws {@link
 *       SemaphoreNotAcquiredException}
 *   <li>{@link SemaphoreReturnDefaultHandler}: Returns null for objects, default values for
 *       primitives
 * </ul>
 *
 * <p>Usage examples:
 *
 * <pre>{@code
 * // Allow up to 10 concurrent database queries
 * @DistributedSemaphore(key = "db-pool", permits = 10, leaseTime = "30s")
 * public void queryDatabase() { }
 *
 * // Allow up to 3 concurrent exports per type
 * @DistributedSemaphore(key = "#{#type + '-export'}", permits = 3, leaseTime = "5m")
 * public void exportData(String type) { }
 *
 * // For scheduled tasks - silently skip if no permit available
 * @DistributedSemaphore(
 *     key = "batch-job",
 *     permits = 5,
 *     leaseTime = "10m",
 *     skipHandler = SemaphoreReturnDefaultHandler.class)
 * public void batchProcess() { }
 * }</pre>
 *
 * <p><b>Important:</b> This annotation uses Redisson's {@code RPermitExpirableSemaphore} which
 * provides automatic permit expiration. Each permit has a lease time after which it is
 * automatically released, preventing permit leaks if a server crashes.
 *
 * @author Garvit Joshi
 * @since 2.0.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DistributedSemaphore {

  /**
   * The unique key for the semaphore. This key is used to identify the semaphore in Redis.
   * Different resources should use different keys.
   *
   * <p>Supports Spring Expression Language (SpEL). SpEL expressions must be wrapped in {@code
   * #{...}} syntax. Use {@code #{#paramName}} to reference method parameters, {@code
   * #{#paramName.property}} to access object properties.
   *
   * <p>Literal keys (without {@code #{...}}) are used as-is and can contain any characters
   * including {@code #}.
   *
   * @return the semaphore key (literal or SpEL expression)
   */
  String key();

  /**
   * The maximum number of concurrent permits allowed for this semaphore.
   *
   * <p>This value is set when the semaphore is first created in Redis. If the semaphore already
   * exists with a different permits value, a warning is logged and the existing value is used.
   *
   * <p>Must be a positive integer (greater than 0).
   *
   * @return the maximum number of concurrent permits, defaults to 1
   */
  int permits() default 1;

  /**
   * The time after which an acquired permit is automatically released. This is required to prevent
   * permit leaks if a server crashes while holding a permit.
   *
   * <p>Accepts duration strings in the following formats:
   *
   * <ul>
   *   <li>Simple format: "10m" (10 minutes), "30s" (30 seconds), "1h" (1 hour)
   *   <li>ISO-8601 format: "PT10M" (10 minutes), "PT30S" (30 seconds)
   * </ul>
   *
   * <p>Use an empty string to use the default from configuration.
   *
   * @return lease time duration string, empty for default
   */
  String leaseTime() default "";

  /**
   * The permit acquisition mode. Determines behavior when no permit is available.
   *
   * @return the acquisition mode, defaults to SKIP_IMMEDIATELY
   */
  AcquisitionMode mode() default AcquisitionMode.SKIP_IMMEDIATELY;

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
   * Defines the behavior when method execution time exceeds the configured lease duration.
   *
   * <p>When a method runs longer than its permit's lease time, the permit may expire while the
   * method is still executing. This can lead to more concurrent executions than intended. This
   * parameter configures how to handle detection of such scenarios after method completion.
   *
   * <ul>
   *   <li>{@link LeaseExpirationBehavior#LOG_WARNING} (default): Log a warning message
   *   <li>{@link LeaseExpirationBehavior#THROW_EXCEPTION}: Throw {@link
   *       in.riido.locksmith.exception.SemaphoreLeaseExpiredException}
   *   <li>{@link LeaseExpirationBehavior#IGNORE}: Silently ignore
   * </ul>
   *
   * @return the lease expiration behavior, defaults to LOG_WARNING
   */
  LeaseExpirationBehavior onLeaseExpired() default LeaseExpirationBehavior.LOG_WARNING;

  /**
   * Custom handler for permit acquisition failures.
   *
   * <p>The handler must have a public no-argument constructor. Built-in handlers:
   *
   * <ul>
   *   <li>{@link SemaphoreThrowExceptionHandler} (default): Throws {@link
   *       SemaphoreNotAcquiredException}
   *   <li>{@link SemaphoreReturnDefaultHandler}: Returns null/default values
   * </ul>
   *
   * @return the skip handler class, defaults to SemaphoreThrowExceptionHandler
   * @see SemaphoreSkipHandler
   */
  Class<? extends SemaphoreSkipHandler> skipHandler() default SemaphoreThrowExceptionHandler.class;
}
