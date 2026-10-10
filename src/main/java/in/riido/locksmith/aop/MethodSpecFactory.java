package in.riido.locksmith.aop;

import in.riido.locksmith.DistributedLock;
import in.riido.locksmith.DistributedSemaphore;
import in.riido.locksmith.LocksmithConfigurationException;
import in.riido.locksmith.OnFailure;
import in.riido.locksmith.aop.MethodSpec.LockSpec;
import in.riido.locksmith.aop.MethodSpec.SemaphoreSpec;
import in.riido.locksmith.autoconfigure.LocksmithProperties;
import in.riido.locksmith.lock.LockFailureHandler;
import in.riido.locksmith.semaphore.SemaphoreFailureHandler;
import in.riido.locksmith.support.KeyTemplate;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.Future;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.Environment;
import org.springframework.expression.Expression;
import org.springframework.expression.ParseException;
import org.springframework.scheduling.annotation.Async;

/**
 * Reads the Locksmith annotations of a method, checks every rule and builds its {@link MethodSpec}.
 * The single place where annotations are read, durations and key templates parsed, and
 * misconfigurations reported.
 */
public class MethodSpecFactory {

  private static final String WAIT_TIME = "waitTime";
  private static final String LEASE_TIME = "leaseTime";
  private static final String PERMITS = "permits";

  /** Matched by name, so Reactive Streams need not be on the classpath. */
  private static final String REACTIVE_STREAMS_PUBLISHER = "org.reactivestreams.Publisher";

  /** The last parameter of a Kotlin suspend function; matched by name, like the publisher. */
  private static final String KOTLIN_CONTINUATION = "kotlin.coroutines.Continuation";

  /**
   * The JVM return type of a Kotlin function declared {@code Unit?}, or with an expression body of
   * that type; matched by name, as Spring's {@code @Async} matches it.
   */
  private static final String KOTLIN_UNIT = "kotlin.Unit";

  private final @NonNull Environment environment;
  private final @NonNull LocksmithProperties properties;

  /**
   * Creates the factory.
   *
   * @param environment resolves {@code ${...}} placeholders in semaphore permit counts
   * @param properties supplies the default semaphore lease time
   */
  public MethodSpecFactory(
      @NonNull Environment environment, @NonNull LocksmithProperties properties) {
    this.environment = environment;
    this.properties = properties;
  }

  /**
   * Reports whether a method carries {@link DistributedLock} or {@link DistributedSemaphore},
   * directly or through an interface or superclass declaration.
   *
   * @param method the method
   * @return {@code true} if either annotation is present
   */
  public static boolean isAnnotated(@NonNull Method method) {
    return AnnotatedElementUtils.findMergedAnnotation(method, DistributedLock.class) != null
        || AnnotatedElementUtils.findMergedAnnotation(method, DistributedSemaphore.class) != null;
  }

  /**
   * Returns the interface method behind a method of a JDK dynamic proxy class, the kind of bean a
   * Feign or HTTP interface client is. The proxy class redeclares every interface method as final
   * and without parameter names, so the annotated interface method is the one to check and to take
   * key variable names from. Returns any other method unchanged.
   *
   * @param method the method, possibly declared by a JDK proxy class
   * @return the annotated interface method, or {@code method}
   */
  public static @NonNull Method interfaceMethodOfJdkProxy(@NonNull Method method) {
    Class<?> proxyClass = method.getDeclaringClass();
    if (!Proxy.isProxyClass(proxyClass)) {
      return method;
    }
    // getMethods, not getMethod: an interface can inherit one method from two unrelated
    // interfaces, and getMethod returns only the first, which may lack the annotation.
    for (Class<?> type : proxyClass.getInterfaces()) {
      for (Method candidate : type.getMethods()) {
        if (candidate.getName().equals(method.getName())
            && Arrays.equals(candidate.getParameterTypes(), method.getParameterTypes())
            && isAnnotated(candidate)) {
          return candidate;
        }
      }
    }
    return method;
  }

  /**
   * Builds the spec of a method. A method without either annotation gets a spec with both parts
   * null.
   *
   * @param method the annotated method
   * @return the spec
   * @throws LocksmithConfigurationException on the first rule the annotations break, naming the
   *     class, the method and the offending attribute and value
   */
  public @NonNull MethodSpec create(@NonNull Method method) {
    DistributedLock lock =
        AnnotatedElementUtils.findMergedAnnotation(method, DistributedLock.class);
    DistributedSemaphore semaphore =
        AnnotatedElementUtils.findMergedAnnotation(method, DistributedSemaphore.class);
    return new MethodSpec(
        lock == null ? null : lockSpec(lock, method),
        semaphore == null ? null : semaphoreSpec(semaphore, method));
  }

  private static @NonNull LockSpec lockSpec(
      @NonNull DistributedLock annotation, @NonNull Method method) {
    Class<DistributedLock> type = DistributedLock.class;
    checkInterceptable(type, method);
    Expression key = parseKey(type, annotation.key(), method);
    Duration waitTime = parseWaitTime(type, annotation.waitTime(), method);
    Duration leaseTime = parseLeaseTime(type, annotation.leaseTime(), method);
    Class<? extends LockFailureHandler> handler =
        annotation.handler() == LockFailureHandler.class ? null : annotation.handler();
    checkHandler(type, annotation.onFailure(), handler, LockFailureHandler.class, method);
    checkReturnType(type, method);
    return new LockSpec(
        key, annotation.type(), waitTime, leaseTime, annotation.onFailure(), handler);
  }

  private @NonNull SemaphoreSpec semaphoreSpec(
      @NonNull DistributedSemaphore annotation, @NonNull Method method) {
    Class<DistributedSemaphore> type = DistributedSemaphore.class;
    checkInterceptable(type, method);
    Expression key = parseKey(type, annotation.key(), method);
    int permits = parsePermits(annotation.permits(), method);
    Duration waitTime = parseWaitTime(type, annotation.waitTime(), method);
    Duration leaseTime = parseLeaseTime(type, annotation.leaseTime(), method);
    Class<? extends SemaphoreFailureHandler> handler =
        annotation.handler() == SemaphoreFailureHandler.class ? null : annotation.handler();
    checkHandler(type, annotation.onFailure(), handler, SemaphoreFailureHandler.class, method);
    checkReturnType(type, method);
    return new SemaphoreSpec(
        key,
        permits,
        waitTime,
        leaseTime == null ? properties.semaphore().leaseTime() : leaseTime,
        annotation.onFailure(),
        handler);
  }

  /**
   * Rejects a private, static or final method: Spring's proxies never intercept one, so the
   * annotation would silently do nothing.
   */
  private static void checkInterceptable(
      @NonNull Class<? extends Annotation> type, @NonNull Method method) {
    int modifiers = method.getModifiers();
    String kind =
        Modifier.isPrivate(modifiers)
            ? "private"
            : Modifier.isStatic(modifiers)
                ? "static"
                : Modifier.isFinal(modifiers) ? "final" : null;
    if (kind != null) {
      throw error(
          type,
          method,
          "the method is "
              + kind
              + ", so Spring's proxy never intercepts it and it would run without coordination;"
              + " make it a public method that is not static or final");
    }
  }

  private static @NonNull Expression parseKey(
      @NonNull Class<? extends Annotation> type, @NonNull String template, @NonNull Method method) {
    if (template.isBlank()) {
      throw error(type, method, "key must not be blank");
    }
    Expression key;
    try {
      key = KeyTemplate.parse(template);
    } catch (ParseException e) {
      throw error(
          type, method, "key [" + template + "] is not a valid template: " + e.getMessage(), e);
    }
    KeyTemplate.validateVariables(key, method);
    return key;
  }

  private int parsePermits(@NonNull String text, @NonNull Method method) {
    Class<DistributedSemaphore> type = DistributedSemaphore.class;
    String resolved;
    try {
      resolved = environment.resolveRequiredPlaceholders(text);
    } catch (IllegalArgumentException e) {
      throw error(
          type, method, PERMITS + " [" + text + "] cannot be resolved: " + e.getMessage(), e);
    }
    String shown =
        resolved.equals(text) ? "[" + text + "]" : "[" + resolved + "] (from [" + text + "])";
    int permits;
    try {
      permits = Integer.parseInt(resolved);
    } catch (NumberFormatException e) {
      throw error(type, method, PERMITS + " " + shown + " is not an integer", e);
    }
    if (permits <= 0) {
      throw error(type, method, PERMITS + " " + shown + " must be greater than zero");
    }
    return permits;
  }

  private static @NonNull Duration parseWaitTime(
      @NonNull Class<? extends Annotation> type, @NonNull String value, @NonNull Method method) {
    if (value.isBlank()) {
      return Duration.ZERO;
    }
    Duration waitTime = parseDuration(type, WAIT_TIME, value, method);
    if (waitTime.isNegative()) {
      throw error(type, method, WAIT_TIME + " [" + value + "] must not be negative");
    }
    return waitTime;
  }

  /** Returns null for a blank value; the caller applies its default. */
  private static @Nullable Duration parseLeaseTime(
      @NonNull Class<? extends Annotation> type, @NonNull String value, @NonNull Method method) {
    if (value.isBlank()) {
      return null;
    }
    Duration leaseTime = parseDuration(type, LEASE_TIME, value, method);
    // The operations reject a lease below one millisecond; Redisson would renew a lock instead.
    if (leaseTime.toMillis() <= 0) {
      throw error(type, method, LEASE_TIME + " [" + value + "] must be positive, at least 1ms");
    }
    return leaseTime;
  }

  private static @NonNull Duration parseDuration(
      @NonNull Class<? extends Annotation> type,
      @NonNull String attribute,
      @NonNull String value,
      @NonNull Method method) {
    try {
      return DurationStyle.detectAndParse(value);
    } catch (IllegalArgumentException e) {
      throw error(
          type, method, attribute + " [" + value + "] is not a duration such as 5s or PT5S", e);
    }
  }

  private static void checkHandler(
      @NonNull Class<? extends Annotation> type,
      @NonNull OnFailure onFailure,
      @Nullable Class<?> handler,
      @NonNull Class<?> handlerInterface,
      @NonNull Method method) {
    if (onFailure == OnFailure.HANDLER && handler == null) {
      throw error(
          type,
          method,
          "onFailure HANDLER requires handler to be set to a "
              + handlerInterface.getName()
              + " bean type");
    }
    if (onFailure != OnFailure.HANDLER && handler != null) {
      throw error(
          type,
          method,
          "handler ["
              + handler.getName()
              + "] is set but onFailure is "
              + onFailure
              + "; set onFailure HANDLER or remove handler");
    }
  }

  /**
   * Rejects a method whose work can outlive its return, since the lock is released on return: a
   * reactive return type, a Kotlin suspend function, and a {@link Future} or {@link
   * CompletionStage}, unless Spring's {@code @Async} runs the whole method under the lock on its
   * worker thread. Also rejects an {@code @Async} method that returns anything but {@code void},
   * Kotlin's {@code Unit}, {@link Future} or {@link CompletableFuture}, the only types Spring's
   * {@code @Async} can return: any other type fails every call.
   */
  private static void checkReturnType(
      @NonNull Class<? extends Annotation> type, @NonNull Method method) {
    Class<?> returnType = method.getReturnType();
    Class<?>[] parameters = method.getParameterTypes();
    if (parameters.length > 0
        && parameters[parameters.length - 1].getName().equals(KOTLIN_CONTINUATION)) {
      throw error(
          type,
          method,
          "Kotlin suspend functions are not supported; use a function that is not suspend");
    }
    if (isReactive(returnType)) {
      throw error(
          type,
          method,
          "reactive return types are not supported, got ["
              + returnType.getName()
              + "]; return a plain value, or a future from an @Async method");
    }
    if (isAsync(method)) {
      if (returnType != void.class
          && !returnType.getName().equals(KOTLIN_UNIT)
          && returnType != Future.class
          && returnType != CompletableFuture.class) {
        throw error(
            type,
            method,
            "@Async methods can return only void, Kotlin Unit, Future or CompletableFuture, got ["
                + returnType.getName()
                + "]; Spring's @Async proxy fails every call of any other return type");
      }
    } else if (Future.class.isAssignableFrom(returnType)
        || CompletionStage.class.isAssignableFrom(returnType)) {
      throw error(
          type,
          method,
          "return type "
              + returnType.getName()
              + " would let the work outlive the lock, which is released when the method returns;"
              + " mark the method @Async and declare Future or CompletableFuture, so the lock"
              + " covers its body on the worker thread");
    }
  }

  private static boolean isReactive(@Nullable Class<?> type) {
    if (type == null) {
      return false;
    }
    if (Flow.Publisher.class.isAssignableFrom(type)
        || type.getName().equals(REACTIVE_STREAMS_PUBLISHER)
        || isReactive(type.getSuperclass())) {
      return true;
    }
    for (Class<?> parent : type.getInterfaces()) {
      if (isReactive(parent)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isAsync(@NonNull Method method) {
    return AnnotatedElementUtils.hasAnnotation(method, Async.class)
        || AnnotatedElementUtils.hasAnnotation(method.getDeclaringClass(), Async.class);
  }

  private static @NonNull LocksmithConfigurationException error(
      @NonNull Class<? extends Annotation> annotation,
      @NonNull Method method,
      @NonNull String detail) {
    return error(annotation, method, detail, null);
  }

  private static @NonNull LocksmithConfigurationException error(
      @NonNull Class<? extends Annotation> annotation,
      @NonNull Method method,
      @NonNull String detail,
      @Nullable Throwable cause) {
    return new LocksmithConfigurationException(
        "@"
            + annotation.getSimpleName()
            + " on "
            + method.getDeclaringClass().getName()
            + "."
            + method.getName()
            + ": "
            + detail,
        cause);
  }
}
