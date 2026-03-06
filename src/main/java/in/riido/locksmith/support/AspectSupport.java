package in.riido.locksmith.support;

import in.riido.locksmith.aspect.DistributedSemaphoreAspect;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Utility class providing common operations shared across all locksmith aspects.
 *
 * <p>Contains reusable logic for method signature formatting, application context checks, and
 * handler resolution that would otherwise be duplicated across {@code DistributedLockAspect},
 * {@code DistributedSemaphoreAspect}, and {@code RateLimitAspect}.
 *
 * @author Garvit Joshi
 * @since 3.0.3
 */
public final class AspectSupport {

  private static final Logger LOG = LoggerFactory.getLogger(AspectSupport.class);

  private AspectSupport() {
    // Utility class
  }

  /**
   * Formats a join point's method signature as "ClassName.methodName".
   *
   * @param joinPoint the join point representing the intercepted method
   * @return the formatted method signature
   */
  @NonNull
  public static String formatMethodSignature(@NonNull ProceedingJoinPoint joinPoint) {
    final MethodSignature signature = (MethodSignature) joinPoint.getSignature();
    return signature.getDeclaringType().getSimpleName() + "." + signature.getName();
  }

  /**
   * Checks if the application context is active and can be used for bean lookups.
   *
   * @param applicationContext the application context to check
   * @return true if the context is active, false otherwise
   */
  public static boolean isApplicationContextActive(@NonNull ApplicationContext applicationContext) {
    if (applicationContext instanceof ConfigurableApplicationContext configurableContext) {
      return configurableContext.isActive();
    }
    // For non-configurable contexts, assume active
    return true;
  }

  /**
   * Resolves a handler instance by first attempting Spring bean lookup, then falling back to
   * reflective instantiation.
   *
   * <p>Resolution order:
   *
   * <ol>
   *   <li>Look up the handler as a Spring bean from ApplicationContext by type
   *   <li>Fall back to reflection-based instantiation (requires public no-arg constructor)
   * </ol>
   *
   * <p>This method does not cache instances. Callers should wrap calls with their own caching
   * (e.g., via {@code ConcurrentHashMap.computeIfAbsent}).
   *
   * @param <T> the handler type
   * @param handlerClass the handler class to resolve
   * @param applicationContext the Spring application context for bean lookup
   * @param debug whether debug logging is enabled
   * @return the resolved handler instance
   * @throws IllegalStateException if the handler cannot be instantiated
   */
  @NonNull
  public static <T> T resolveHandler(
      @NonNull Class<? extends T> handlerClass,
      @NonNull ApplicationContext applicationContext,
      boolean debug) {
    // First, try to get the handler as a Spring bean (only if context is active)
    if (isApplicationContextActive(applicationContext)) {
      try {
        T bean = applicationContext.getBean(handlerClass);
        if (bean != null) {
          return bean;
        }
      } catch (BeansException ignored) {
        // Bean not found, will fall back to reflection
      }
    } else {
      if (debug) {
        LOG.info(
            "ApplicationContext is not active, skipping Spring bean lookup for handler: {}",
            handlerClass.getName());
      }
    }
    // Not a Spring bean, fall back to reflection
    if (debug) {
      LOG.info(
          "Handler {} not found as Spring bean, falling back to reflection-based instantiation",
          handlerClass.getName());
    }

    // Fall back to reflection-based instantiation
    try {
      return handlerClass.getDeclaredConstructor().newInstance();
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(
          "Failed to instantiate skip handler: "
              + handlerClass.getName()
              + ". Ensure it is a Spring bean or has a public no-argument constructor.",
          e);
    }
  }
}
