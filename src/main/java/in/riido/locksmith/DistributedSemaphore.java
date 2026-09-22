package in.riido.locksmith;

import in.riido.locksmith.semaphore.SemaphoreFailureHandler;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Runs the annotated method while holding one permit of a distributed semaphore. The permit is
 * released when the method returns or throws; for a method that returns a {@code CompletionStage},
 * when that stage completes.
 *
 * <pre>{@code
 * @DistributedSemaphore(key = "reports", permits = "${reports.max-concurrent}", waitTime = "2s")
 * public Report build(String id) { ... }
 * }</pre>
 *
 * <p>Every attribute is checked at startup; a misconfigured annotation fails the application
 * context refresh with a {@link LocksmithConfigurationException}. The annotation is honoured only
 * on calls through the Spring proxy: a call on {@code this} bypasses it. If a method also carries
 * {@link DistributedLock}, the permit is acquired before the lock and released after it.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DistributedSemaphore {

  /**
   * The semaphore key, as a template: literal text with {@code #{...}} SpEL islands whose variables
   * are the method parameters by name, {@code #pN} or {@code #aN}; {@code #this} is allowed inside
   * a selection or projection. Must not be blank. The Redis key is {@code
   * <keyPrefix>semaphore:<resolved key>}.
   *
   * @return the key template
   */
  String key();

  /**
   * The number of permits, as a string so a {@code ${...}} placeholder works. Must resolve to an
   * integer greater than zero.
   *
   * @return the permit count
   */
  String permits();

  /**
   * How long to wait for a permit, as {@code "5s"} or {@code "PT5S"}. The default {@code ""} means
   * try once and give up. Must not be negative.
   *
   * @return the wait time
   */
  String waitTime() default "";

  /**
   * The lease of a permit, as {@code "30s"} or {@code "PT30S"}: the permit expires this long after
   * it is acquired. The default {@code ""} means {@code locksmith.semaphore.lease-time}. Must be at
   * least one millisecond when set.
   *
   * @return the lease time
   */
  String leaseTime() default "";

  /**
   * What the method does when no permit is acquired.
   *
   * @return the failure policy; defaults to {@link OnFailure#THROW}
   */
  OnFailure onFailure() default OnFailure.THROW;

  /**
   * The failure handler, resolved as the single Spring bean of this type. Required when {@link
   * #onFailure()} is {@link OnFailure#HANDLER} and not allowed otherwise. The default, the {@link
   * SemaphoreFailureHandler} interface itself, means not set.
   *
   * @return the handler type
   */
  Class<? extends SemaphoreFailureHandler> handler() default SemaphoreFailureHandler.class;
}
