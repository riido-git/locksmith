package in.riido.locksmith;

import in.riido.locksmith.lock.LockFailureHandler;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Runs the annotated method while holding a distributed lock on a key. The lock is released when
 * the method returns or throws; for a method that returns a {@code CompletionStage}, when that
 * stage completes.
 *
 * <pre>{@code
 * @DistributedLock(key = "order:#{#orderId}", waitTime = "5s")
 * public void process(String orderId) { ... }
 * }</pre>
 *
 * <p>Every attribute is checked at startup; a misconfigured annotation fails the application
 * context refresh with a {@link LocksmithConfigurationException}. The annotation is honoured only
 * on calls through the Spring proxy: a call on {@code this} bypasses it.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DistributedLock {

  /**
   * The lock key, as a template: literal text with {@code #{...}} SpEL islands whose variables are
   * the method parameters by name, {@code #pN} or {@code #aN}, for example {@code
   * "user:#{#userId}"}; {@code #this} is allowed inside a selection or projection. Must not be
   * blank. The Redis key is {@code <keyPrefix>lock:<resolved key>}, or {@code
   * <keyPrefix>rwlock:<resolved key>} for read and write locks.
   *
   * @return the key template
   */
  String key();

  /**
   * The lock type. Read and write locks of one key share the Redis key.
   *
   * @return the lock type; defaults to {@link LockType#REENTRANT}
   */
  LockType type() default LockType.REENTRANT;

  /**
   * How long to wait for the lock, as {@code "5s"} or {@code "PT5S"}. The default {@code ""} means
   * try once and give up. Must not be negative.
   *
   * @return the wait time
   */
  String waitTime() default "";

  /**
   * A fixed lease, as {@code "30s"} or {@code "PT30S"}: the lock expires this long after it is
   * acquired and is not renewed. The default {@code ""} means the lock is renewed for as long as it
   * is held. Must be at least one millisecond when set.
   *
   * @return the lease time
   */
  String leaseTime() default "";

  /**
   * What the method does when the lock is not acquired.
   *
   * @return the failure policy; defaults to {@link OnFailure#THROW}
   */
  OnFailure onFailure() default OnFailure.THROW;

  /**
   * The failure handler, resolved as the single Spring bean of this type. Required when {@link
   * #onFailure()} is {@link OnFailure#HANDLER} and not allowed otherwise. The default, the {@link
   * LockFailureHandler} interface itself, means not set.
   *
   * @return the handler type
   */
  Class<? extends LockFailureHandler> handler() default LockFailureHandler.class;
}
