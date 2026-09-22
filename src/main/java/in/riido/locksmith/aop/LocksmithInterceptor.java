package in.riido.locksmith.aop;

import in.riido.locksmith.aop.MethodSpec.LockSpec;
import in.riido.locksmith.aop.MethodSpec.SemaphoreSpec;
import in.riido.locksmith.lock.AsyncLockSupport;
import in.riido.locksmith.lock.LockFailureContext;
import in.riido.locksmith.lock.LockHandle;
import in.riido.locksmith.lock.LockNotAcquiredException;
import in.riido.locksmith.lock.LockOperations;
import in.riido.locksmith.semaphore.AsyncPermitSupport;
import in.riido.locksmith.semaphore.PermitHandle;
import in.riido.locksmith.semaphore.SemaphoreFailureContext;
import in.riido.locksmith.semaphore.SemaphoreNotAcquiredException;
import in.riido.locksmith.semaphore.SemaphoreOperations;
import in.riido.locksmith.support.KeyTemplate;
import in.riido.locksmith.support.ReturnDefaults;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.function.SingletonSupplier;

/**
 * Runs an annotated method under its semaphore permit and its lock. A thin adapter: the keys are
 * resolved from the method arguments, the permit is taken through {@link SemaphoreOperations} and
 * the lock through {@link LockOperations}, and the failure policy of the annotation decides the
 * result when either is not acquired. The permit is acquired before the lock and released after it,
 * so a caller never holds a lock while queued for a permit.
 *
 * <p>A method that returns a {@code CompletionStage} keeps both until the stage it returns
 * completes, normally, exceptionally or by cancellation, and the caller gets that same stage. Its
 * lock is owned by a generated id instead of the calling thread, so the thread that completes the
 * stage can release it; that lock is not reentrant.
 *
 * <p>The operations and the factory are resolved on first use, not in the constructor. The advisor,
 * and with it this interceptor, is created while bean post-processors are still being registered;
 * resolving the operations then would create them, the properties and the adopter's {@code
 * RedissonClient} too early for every post-processor to apply to them.
 */
public class LocksmithInterceptor implements MethodInterceptor {

  private final @NonNull SingletonSupplier<LockOperations> lockOperations;
  private final @NonNull SingletonSupplier<SemaphoreOperations> semaphoreOperations;
  private final @NonNull SingletonSupplier<MethodSpecFactory> factory;
  private final @NonNull BeanFactory beanFactory;
  private final Map<Method, MethodSpec> specs = new ConcurrentHashMap<>();
  private final Map<Class<?>, Object> handlers = new ConcurrentHashMap<>();

  /**
   * Creates the interceptor.
   *
   * @param lockOperations takes and releases the locks; resolved on first use
   * @param semaphoreOperations takes and releases the semaphore permits; resolved on first use
   * @param factory builds the spec of a method the validator did not register; resolved on first
   *     use
   * @param beanFactory resolves failure handler beans on first use
   */
  public LocksmithInterceptor(
      @NonNull ObjectProvider<LockOperations> lockOperations,
      @NonNull ObjectProvider<SemaphoreOperations> semaphoreOperations,
      @NonNull ObjectProvider<MethodSpecFactory> factory,
      @NonNull BeanFactory beanFactory) {
    this.lockOperations = SingletonSupplier.of(lockOperations::getObject);
    this.semaphoreOperations = SingletonSupplier.of(semaphoreOperations::getObject);
    this.factory = SingletonSupplier.of(factory::getObject);
    this.beanFactory = beanFactory;
  }

  /**
   * Stores the spec built at startup for a method, so the first call does not build it again.
   *
   * @param method the most specific method on the bean's class
   * @param spec the spec of that method
   */
  public void register(@NonNull Method method, @NonNull MethodSpec spec) {
    specs.put(method, spec);
  }

  /**
   * Acquires the permit, then the lock, of the invoked method, proceeds, and releases the lock and
   * then the permit whether the method returns or throws. A method that returns a {@code
   * CompletionStage} releases them when that stage completes instead, or at once if it throws or
   * returns null. When either is not acquired, the method does not run, a permit already held is
   * released, and the failure policy of the annotation that failed decides the result.
   *
   * @param invocation the intercepted call
   * @return the method result, or the failure policy result
   * @throws SemaphoreNotAcquiredException if the permit is not acquired and the policy is {@code
   *     THROW}
   * @throws LockNotAcquiredException if the lock is not acquired and the policy is {@code THROW}
   * @throws in.riido.locksmith.LocksmithConfigurationException if an annotation is misconfigured or
   *     a key resolves to null or blank
   * @throws Throwable whatever the method, the failure handler or Redisson throws, unchanged
   */
  @Override
  public @Nullable Object invoke(@NonNull MethodInvocation invocation) throws Throwable {
    Object target = invocation.getThis();
    Method method =
        AopUtils.getMostSpecificMethod(
            invocation.getMethod(), target == null ? null : AopUtils.getTargetClass(target));
    MethodSpec spec = specs.computeIfAbsent(method, m -> factory.obtain().create(m));
    Object[] args = invocation.getArguments();
    SemaphoreSpec semaphore = spec.semaphore();
    LockSpec lock = spec.lock();
    if (CompletionStage.class.isAssignableFrom(method.getReturnType())) {
      return invokeAsync(invocation, semaphore, lock, method, args);
    }
    try (PermitHandle permit = semaphore == null ? null : acquire(semaphore, method, args)) {
      if (permit != null && !permit.acquired()) {
        return semaphoreFailure(semaphore, permit.key(), method, args);
      }
      try (LockHandle held = lock == null ? null : acquire(lock, method, args)) {
        if (held != null && !held.acquired()) {
          if (permit != null) {
            permit.close();
          }
          return lockFailure(lock, held.key(), method, args);
        }
        return invocation.proceed();
      }
    }
  }

  /**
   * {@link #invoke} for a method that returns a {@code CompletionStage}: acquires the same way, and
   * hands the permit and the lock over to the returned stage, which releases them on completion.
   */
  private @Nullable Object invokeAsync(
      @NonNull MethodInvocation invocation,
      @Nullable SemaphoreSpec semaphore,
      @Nullable LockSpec lock,
      @NonNull Method method,
      @Nullable Object @NonNull [] args)
      throws Throwable {
    PermitHandle permit = semaphore == null ? null : acquire(semaphore, method, args);
    if (permit != null && !permit.acquired()) {
      return semaphoreFailure(semaphore, permit.key(), method, args);
    }
    LockHandle held = null;
    boolean handedOver = false;
    try {
      held = lock == null ? null : AsyncLockSupport.acquire(builder(lock, method, args));
      if (held != null && !held.acquired()) {
        if (permit != null) {
          permit.close();
        }
        return lockFailure(lock, held.key(), method, args);
      }
      Object result = invocation.proceed();
      if (result instanceof CompletionStage<?> stage) {
        LockHandle acquired = held;
        stage.whenComplete((value, failure) -> releaseAsync(acquired, permit));
        handedOver = true;
      }
      return result;
    } finally {
      if (!handedOver) {
        if (held != null) {
          held.close();
        }
        if (permit != null) {
          permit.close();
        }
      }
    }
  }

  /** Releases the lock, then the permit, without blocking the thread that completed the stage. */
  private static void releaseAsync(@Nullable LockHandle held, @Nullable PermitHandle permit) {
    CompletionStage<Void> lockReleased =
        held == null ? CompletableFuture.completedFuture(null) : AsyncLockSupport.release(held);
    if (permit != null) {
      lockReleased.thenCompose(released -> AsyncPermitSupport.release(permit));
    }
  }

  private @NonNull PermitHandle acquire(
      @NonNull SemaphoreSpec spec, @NonNull Method method, @Nullable Object @NonNull [] args) {
    return semaphoreOperations
        .obtain()
        .key(KeyTemplate.evaluate(spec.key(), method, args))
        .permits(spec.permits())
        .waitTime(spec.waitTime())
        .leaseTime(spec.leaseTime())
        .acquire();
  }

  private @NonNull LockHandle acquire(
      @NonNull LockSpec spec, @NonNull Method method, @Nullable Object @NonNull [] args) {
    return builder(spec, method, args).acquire();
  }

  private LockOperations.@NonNull Builder builder(
      @NonNull LockSpec spec, @NonNull Method method, @Nullable Object @NonNull [] args) {
    LockOperations.Builder builder =
        lockOperations
            .obtain()
            .key(KeyTemplate.evaluate(spec.key(), method, args))
            .type(spec.type())
            .waitTime(spec.waitTime());
    if (spec.leaseTime() != null) {
      builder.leaseTime(spec.leaseTime());
    }
    return builder;
  }

  private @Nullable Object semaphoreFailure(
      @NonNull SemaphoreSpec spec,
      @NonNull String fullKey,
      @NonNull Method method,
      @Nullable Object @NonNull [] args) {
    return switch (spec.onFailure()) {
      case THROW ->
          throw new SemaphoreNotAcquiredException(fullKey, spec.permits(), spec.waitTime());
      case SKIP -> ReturnDefaults.forType(method.getReturnType());
      case HANDLER ->
          handler(spec.handlerType())
              .onFailure(
                  new SemaphoreFailureContext(
                      fullKey, spec.permits(), method, args, spec.waitTime()));
    };
  }

  private @Nullable Object lockFailure(
      @NonNull LockSpec spec,
      @NonNull String fullKey,
      @NonNull Method method,
      @Nullable Object @NonNull [] args) {
    return switch (spec.onFailure()) {
      case THROW -> throw new LockNotAcquiredException(fullKey, spec.waitTime());
      case SKIP -> ReturnDefaults.forType(method.getReturnType());
      case HANDLER ->
          handler(spec.handlerType())
              .onFailure(new LockFailureContext(fullKey, method, args, spec.waitTime()));
    };
  }

  private <T> @NonNull T handler(@Nullable Class<? extends T> handlerType) {
    // MethodSpecFactory guarantees a handler type whenever onFailure is HANDLER.
    return handlerType.cast(handlers.computeIfAbsent(handlerType, beanFactory::getBean));
  }
}
