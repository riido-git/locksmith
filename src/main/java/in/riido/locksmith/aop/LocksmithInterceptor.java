package in.riido.locksmith.aop;

import in.riido.locksmith.aop.MethodSpec.LockSpec;
import in.riido.locksmith.aop.MethodSpec.SemaphoreSpec;
import in.riido.locksmith.lock.LockFailureContext;
import in.riido.locksmith.lock.LockHandle;
import in.riido.locksmith.lock.LockNotAcquiredException;
import in.riido.locksmith.lock.LockOperations;
import in.riido.locksmith.semaphore.PermitHandle;
import in.riido.locksmith.semaphore.SemaphoreFailureContext;
import in.riido.locksmith.semaphore.SemaphoreNotAcquiredException;
import in.riido.locksmith.semaphore.SemaphoreOperations;
import in.riido.locksmith.support.KeyTemplate;
import in.riido.locksmith.support.ReturnDefaults;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
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

  /**
   * Creates the interceptor.
   *
   * @param lockOperations takes and releases the locks; resolved on first use
   * @param semaphoreOperations takes and releases the semaphore permits; resolved on first use
   * @param factory builds the spec of a method on its first call; resolved on first use
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
   * Acquires the permit, then the lock, of the invoked method, proceeds, and releases the lock and
   * then the permit whether the method returns or throws. When either is not acquired, the method
   * does not run, a permit already held is released, and the failure policy of the annotation that
   * failed decides the result.
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
    // A bean that is itself a JDK proxy, such as a Spring Data repository, carries the annotations
    // on its interfaces: resolve against the proxy class, as the startup check does, not against
    // the class of the object behind it.
    Class<?> targetClass =
        target == null
            ? null
            : Proxy.isProxyClass(target.getClass())
                ? target.getClass()
                : AopUtils.getTargetClass(target);
    Method method =
        MethodSpecFactory.interfaceMethodOfJdkProxy(
            AopUtils.getMostSpecificMethod(invocation.getMethod(), targetClass));
    MethodSpec spec = specs.computeIfAbsent(method, m -> factory.obtain().create(m));
    Object[] args = invocation.getArguments();
    SemaphoreSpec semaphore = spec.semaphore();
    LockSpec lock = spec.lock();
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
    LockOperations.Builder builder =
        lockOperations
            .obtain()
            .key(KeyTemplate.evaluate(spec.key(), method, args))
            .type(spec.type())
            .waitTime(spec.waitTime());
    if (spec.leaseTime() != null) {
      builder.leaseTime(spec.leaseTime());
    }
    return builder.acquire();
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
    return beanFactory.getBean(handlerType);
  }
}
