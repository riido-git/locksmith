package in.riido.locksmith.support;

import in.riido.locksmith.DistributedLock;
import in.riido.locksmith.DistributedSemaphore;
import in.riido.locksmith.LockType;
import in.riido.locksmith.LocksmithConfigurationException;
import in.riido.locksmith.aop.LocksmithInterceptor;
import in.riido.locksmith.aop.MethodSpec;
import in.riido.locksmith.aop.MethodSpecFactory;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

/**
 * Checks the Locksmith annotations of every bean at startup, so a misconfiguration fails the
 * context refresh instead of the first call. Each spec it builds is registered with the {@link
 * LocksmithInterceptor}. It never calls {@code getBean} on the handler types it checks, so it
 * triggers no early instantiation.
 *
 * <p>It is {@link PriorityOrdered}, so it runs before every {@link Ordered} post-processor,
 * including the auto-proxy creator. It therefore sees the raw bean: {@code
 * ClassUtils.getUserClass(bean)} is the implementing class even when beans get JDK proxies, error
 * messages name that class, and annotations on implementing methods are checked at startup.
 */
public class AnnotationValidator implements BeanPostProcessor, PriorityOrdered {

  private final @NonNull ObjectProvider<LocksmithInterceptor> interceptor;
  private final @NonNull ObjectProvider<MethodSpecFactory> factory;
  private final @NonNull ListableBeanFactory beanFactory;

  /** The first method seen with each {@code @DistributedLock} key text, and its lock type. */
  private final Map<String, KeyUse> lockKeys = new ConcurrentHashMap<>();

  /**
   * Creates the validator. The providers are resolved on the first annotated bean, not here.
   *
   * @param interceptor the interceptor the specs are registered with
   * @param factory builds and checks the spec of each annotated method
   * @param beanFactory counts the beans of each failure handler type
   */
  public AnnotationValidator(
      @NonNull ObjectProvider<LocksmithInterceptor> interceptor,
      @NonNull ObjectProvider<MethodSpecFactory> factory,
      @NonNull ListableBeanFactory beanFactory) {
    this.interceptor = interceptor;
    this.factory = factory;
    this.beanFactory = beanFactory;
  }

  /**
   * Builds the spec of every annotated method of the bean's class, checks that each failure handler
   * type has exactly one bean and that no {@code @DistributedLock} key text is used both with
   * {@code REENTRANT} and with {@code READ} or {@code WRITE}, and registers the spec with the
   * interceptor.
   *
   * @param bean the initialized bean
   * @param beanName the bean name
   * @return the bean, unchanged
   * @throws LocksmithConfigurationException on the first misconfigured annotation, which aborts the
   *     context refresh
   */
  @Override
  public @NonNull Object postProcessAfterInitialization(
      @NonNull Object bean, @NonNull String beanName) {
    Class<?> target = ClassUtils.getUserClass(bean);
    for (Method method :
        ReflectionUtils.getUniqueDeclaredMethods(target, MethodSpecFactory::isAnnotated)) {
      MethodSpec spec = factory.getObject().create(method);
      if (spec.lock() != null) {
        requireSingleHandlerBean(DistributedLock.class, spec.lock().handlerType(), method);
        requireOneLockKind(spec.lock().key().getExpressionString(), spec.lock().type(), method);
      }
      if (spec.semaphore() != null) {
        requireSingleHandlerBean(
            DistributedSemaphore.class, spec.semaphore().handlerType(), method);
      }
      interceptor.getObject().register(method, spec);
    }
    return bean;
  }

  /**
   * Returns {@link Ordered#LOWEST_PRECEDENCE}: last among the {@link PriorityOrdered}
   * post-processors, and still before every {@link Ordered} one.
   *
   * @return the order
   */
  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }

  /**
   * Fails when a key text is used with {@code REENTRANT} on one method and with {@code READ} or
   * {@code WRITE} on another: the two kinds live at different Redis keys, so they would not exclude
   * each other.
   */
  private void requireOneLockKind(
      @NonNull String keyText, @NonNull LockType type, @NonNull Method method) {
    KeyUse first = lockKeys.putIfAbsent(keyText, new KeyUse(method, type));
    if (first == null || (first.type() == LockType.REENTRANT) == (type == LockType.REENTRANT)) {
      return;
    }
    throw new LocksmithConfigurationException(
        "@DistributedLock key ["
            + keyText
            + "] is used with "
            + first.type()
            + " on "
            + describe(first.method())
            + " and with "
            + type
            + " on "
            + describe(method)
            + "; a REENTRANT lock and a READ/WRITE lock of one key are separate locks and do not"
            + " exclude each other. Pick one kind for that key: REENTRANT, or READ/WRITE.");
  }

  private static @NonNull String describe(@NonNull Method method) {
    return method.getDeclaringClass().getName() + "." + method.getName();
  }

  /** A method that uses a lock key text, and the lock type it uses it with. */
  private record KeyUse(@NonNull Method method, @NonNull LockType type) {}

  private void requireSingleHandlerBean(
      @NonNull Class<? extends Annotation> annotation,
      @Nullable Class<?> handlerType,
      @NonNull Method method) {
    if (handlerType == null) {
      return;
    }
    int found = beanFactory.getBeanNamesForType(handlerType).length;
    if (found != 1) {
      throw new LocksmithConfigurationException(
          "@"
              + annotation.getSimpleName()
              + " on "
              + method.getDeclaringClass().getName()
              + "."
              + method.getName()
              + ": onFailure HANDLER requires exactly one bean of handler type ["
              + handlerType.getName()
              + "], found "
              + found);
    }
  }
}
