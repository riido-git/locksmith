package in.riido.locksmith.aop;

import java.lang.reflect.Method;
import org.jspecify.annotations.NonNull;
import org.springframework.aop.support.AopUtils;
import org.springframework.aop.support.StaticMethodMatcherPointcutAdvisor;
import org.springframework.core.Ordered;

/**
 * Applies {@link LocksmithInterceptor} to every method that carries {@code @DistributedLock} or
 * {@code @DistributedSemaphore}, on the class or on an interface it implements. Ordered at {@code
 * Ordered.LOWEST_PRECEDENCE - 1}: inside Spring Security's method authorization, so a call it
 * denies never reaches Redis, and outside advice at the default order, such as transactions and
 * caching, so a transaction commits before the lock is released.
 */
public class LocksmithAdvisor extends StaticMethodMatcherPointcutAdvisor {

  /**
   * Creates the advisor.
   *
   * @param interceptor the advice applied to matching methods
   */
  public LocksmithAdvisor(@NonNull LocksmithInterceptor interceptor) {
    super(interceptor);
    setOrder(Ordered.LOWEST_PRECEDENCE - 1);
  }

  /**
   * Matches a method whose most specific implementation on the target class carries either
   * annotation.
   *
   * @param method the candidate method
   * @param targetClass the target class
   * @return {@code true} if the method is annotated
   */
  @Override
  public boolean matches(@NonNull Method method, @NonNull Class<?> targetClass) {
    return MethodSpecFactory.isAnnotated(AopUtils.getMostSpecificMethod(method, targetClass));
  }
}
