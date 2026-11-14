package in.riido.locksmith.support;

import in.riido.locksmith.DistributedLock;
import in.riido.locksmith.DistributedSemaphore;
import in.riido.locksmith.LockType;
import in.riido.locksmith.LocksmithConfigurationException;
import in.riido.locksmith.aop.MethodSpec;
import in.riido.locksmith.aop.MethodSpecFactory;
import in.riido.locksmith.lock.LockFailureHandler;
import in.riido.locksmith.semaphore.SemaphoreFailureHandler;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

/**
 * Checks the Locksmith annotations of every bean at startup, so a misconfiguration fails the
 * context refresh instead of the first call. The specs it builds only serve the check; the
 * interceptor builds its own on a method's first call.
 *
 * <p>The failure handler of each annotation is counted once every singleton is created, when the
 * class of a handler declared by its interface, such as {@code @Bean LockFailureHandler audit()},
 * is known; for a bean created after that, at once. It calls {@code getBean} only on a handler
 * whose type is a JDK proxy class, to read the class behind it; such a handler is already created,
 * except a lazy {@code ProxyFactoryBean} with explicit interfaces, which reports that type before
 * it is created.
 *
 * <p>It is {@link PriorityOrdered}, so it runs before every {@link Ordered} post-processor,
 * including the auto-proxy creator. It therefore sees the raw bean: {@code
 * ClassUtils.getUserClass(bean)} is the implementing class even when beans get JDK proxies, error
 * messages name that class, and annotations on implementing methods are checked at startup. A bean
 * that is itself a JDK dynamic proxy, such as a Feign client, is checked through the annotated
 * methods of its interfaces.
 */
public class AnnotationValidator
    implements BeanPostProcessor, PriorityOrdered, SmartInitializingSingleton {

  private final @NonNull ObjectProvider<MethodSpecFactory> factory;
  private final @NonNull ListableBeanFactory beanFactory;

  /** Handler counts waiting for every singleton to be created; guards the flag below too. */
  private final List<Runnable> handlerChecks = new ArrayList<>();

  private boolean singletonsInstantiated;

  /** The first method seen with each {@code @DistributedLock} key text, and its lock type. */
  private final Map<String, KeyUse> lockKeys = new ConcurrentHashMap<>();

  /** The first method seen with each {@code @DistributedSemaphore} key text, and its count. */
  private final Map<String, PermitsUse> semaphoreKeys = new ConcurrentHashMap<>();

  /**
   * Creates the validator. The provider is resolved on the first annotated bean, not here.
   *
   * @param factory builds and checks the spec of each annotated method
   * @param beanFactory counts the beans of each failure handler type
   */
  public AnnotationValidator(
      @NonNull ObjectProvider<MethodSpecFactory> factory,
      @NonNull ListableBeanFactory beanFactory) {
    this.factory = factory;
    this.beanFactory = beanFactory;
  }

  /**
   * Builds the spec of every annotated method of the bean's class, reading return types as that
   * class binds their type variables, checks that Spring's proxy of the bean can intercept each,
   * that no {@code @DistributedLock} key text is used both with {@code REENTRANT} and with {@code
   * READ} or {@code WRITE}, and that no {@code @DistributedSemaphore} key text is used with two
   * permit counts, and has each failure handler type counted, which must have exactly one bean.
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
    for (Method declared :
        ReflectionUtils.getUniqueDeclaredMethods(target, MethodSpecFactory::isAnnotated)) {
      Method method = MethodSpecFactory.interfaceMethodOfJdkProxy(declared);
      MethodSpec spec = factory.getObject().create(method, target);
      if (spec.lock() != null) {
        requireInterceptable(DistributedLock.class, method, target);
        Class<?> handlerType = spec.lock().handlerType();
        checkHandler(
            () ->
                requireSingleHandlerBean(
                    DistributedLock.class, handlerType, LockFailureHandler.class, method));
        requireOneLockKind(KeyTemplate.describe(spec.lock().key()), spec.lock().type(), method);
      }
      if (spec.semaphore() != null) {
        requireInterceptable(DistributedSemaphore.class, method, target);
        Class<?> handlerType = spec.semaphore().handlerType();
        checkHandler(
            () ->
                requireSingleHandlerBean(
                    DistributedSemaphore.class,
                    handlerType,
                    SemaphoreFailureHandler.class,
                    method));
        requireOneCount(
            KeyTemplate.describe(spec.semaphore().key()), spec.semaphore().permits(), method);
      }
    }
    return bean;
  }

  /**
   * Counts the failure handlers of the beans checked so far, now that every singleton is created.
   *
   * @throws LocksmithConfigurationException if a handler type does not have exactly one bean, which
   *     aborts the context refresh
   */
  @Override
  public void afterSingletonsInstantiated() {
    List<Runnable> checks;
    synchronized (handlerChecks) {
      singletonsInstantiated = true;
      checks = List.copyOf(handlerChecks);
      handlerChecks.clear();
    }
    checks.forEach(Runnable::run);
  }

  /** Runs a handler count once every singleton is created, or now if they already are. */
  private void checkHandler(@NonNull Runnable check) {
    synchronized (handlerChecks) {
      if (!singletonsInstantiated) {
        handlerChecks.add(check);
        return;
      }
    }
    check.run();
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
   * Fails on a package-private method declared in another package than the bean class, or loaded by
   * another class loader: Spring's class-based proxy cannot override it, so it never intercepts it
   * and the annotation would silently do nothing. {@link MethodSpecFactory} already refuses
   * private, static and final methods.
   */
  private static void requireInterceptable(
      @NonNull Class<? extends Annotation> annotation,
      @NonNull Method method,
      @NonNull Class<?> beanClass) {
    int modifiers = method.getModifiers();
    Class<?> declaring = method.getDeclaringClass();
    if (Modifier.isPublic(modifiers)
        || Modifier.isProtected(modifiers)
        || (declaring.getPackageName().equals(beanClass.getPackageName())
            && declaring.getClassLoader() == beanClass.getClassLoader())) {
      return;
    }
    throw new LocksmithConfigurationException(
        "@"
            + annotation.getSimpleName()
            + " on "
            + describe(method)
            + ": the method is package-private but bean class "
            + beanClass.getName()
            + " is in another package or class loader, so Spring's proxy never intercepts it and"
            + " it would run without coordination; make it public or protected");
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

  /**
   * Fails when a key text is used with one permit count on one method and with another count on
   * another: Redis keeps one count per semaphore, so each would overwrite the other's.
   */
  private void requireOneCount(@NonNull String keyText, int permits, @NonNull Method method) {
    PermitsUse first = semaphoreKeys.putIfAbsent(keyText, new PermitsUse(method, permits));
    if (first == null || first.permits() == permits) {
      return;
    }
    throw new LocksmithConfigurationException(
        "@DistributedSemaphore key ["
            + keyText
            + "] is used with permits "
            + first.permits()
            + " on "
            + describe(first.method())
            + " and with permits "
            + permits
            + " on "
            + describe(method)
            + "; Redis keeps one count per semaphore, so each would overwrite the other's. Use one"
            + " count for that key, or give each count its own key.");
  }

  private static @NonNull String describe(@NonNull Method method) {
    return method.getDeclaringClass().getName() + "." + method.getName();
  }

  /** A method that uses a lock key text, and the lock type it uses it with. */
  private record KeyUse(@NonNull Method method, @NonNull LockType type) {}

  /** A method that uses a semaphore key text, and the permit count it uses it with. */
  private record PermitsUse(@NonNull Method method, int permits) {}

  /**
   * Fails unless exactly one bean is of the handler type, by the rule the interceptor finds it
   * with: {@link MethodSpecFactory#failureHandlerNames}.
   */
  private void requireSingleHandlerBean(
      @NonNull Class<? extends Annotation> annotation,
      @Nullable Class<?> handlerType,
      @NonNull Class<?> handlerInterface,
      @NonNull Method method) {
    if (handlerType == null) {
      return;
    }
    int found =
        MethodSpecFactory.failureHandlerNames(beanFactory, handlerType, handlerInterface).size();
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
              + found
              + (found == 0
                  ? "; for a lazy or prototype @Bean, declare the concrete handler type as its"
                      + " return type so startup can identify it without creating it"
                  : ""));
    }
  }
}
