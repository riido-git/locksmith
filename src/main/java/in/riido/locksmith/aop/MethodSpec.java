package in.riido.locksmith.aop;

import in.riido.locksmith.LockType;
import in.riido.locksmith.OnFailure;
import in.riido.locksmith.lock.LockFailureHandler;
import in.riido.locksmith.semaphore.SemaphoreFailureHandler;
import java.time.Duration;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.expression.Expression;

/**
 * The checked, parsed form of the Locksmith annotations on one method, built by {@link
 * MethodSpecFactory}.
 *
 * @param lock the lock settings, or null if the method has no {@code @DistributedLock}
 * @param semaphore the semaphore settings, or null if the method has no
 *     {@code @DistributedSemaphore}
 */
public record MethodSpec(@Nullable LockSpec lock, @Nullable SemaphoreSpec semaphore) {

  /**
   * The settings of a {@code @DistributedLock}.
   *
   * @param key the parsed key template
   * @param type the lock type
   * @param waitTime how long to wait; zero means try once
   * @param leaseTime the fixed lease, or null for renewal while held
   * @param onFailure what to do when the lock is not acquired
   * @param handlerType the handler bean type, set only when {@code onFailure} is {@code HANDLER}
   */
  public record LockSpec(
      @NonNull Expression key,
      @NonNull LockType type,
      @NonNull Duration waitTime,
      @Nullable Duration leaseTime,
      @NonNull OnFailure onFailure,
      @Nullable Class<? extends LockFailureHandler> handlerType) {}

  /**
   * The settings of a {@code @DistributedSemaphore}.
   *
   * @param key the parsed key template
   * @param permits the permit count, greater than zero
   * @param waitTime how long to wait; zero means try once
   * @param leaseTime the lease of a permit
   * @param onFailure what to do when no permit is acquired
   * @param handlerType the handler bean type, set only when {@code onFailure} is {@code HANDLER}
   */
  public record SemaphoreSpec(
      @NonNull Expression key,
      int permits,
      @NonNull Duration waitTime,
      @NonNull Duration leaseTime,
      @NonNull OnFailure onFailure,
      @Nullable Class<? extends SemaphoreFailureHandler> handlerType) {}
}
