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
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.Future;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.ReactiveAdapterRegistry;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.Environment;
import org.springframework.expression.Expression;
import org.springframework.expression.ParseException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.util.ReflectionUtils;

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

  /**
   * Kotlin's flow and deferred value, matched by name like the publisher: Spring's reactive adapter
   * registry knows them only when kotlinx-coroutines-reactor is present.
   */
  private static final String KOTLIN_FLOW = "kotlinx.coroutines.flow.Flow";

  private static final String KOTLIN_DEFERRED = "kotlinx.coroutines.Deferred";

  /** The last parameter of a Kotlin suspend function; matched by name, like the publisher. */
  private static final String KOTLIN_CONTINUATION = "kotlin.coroutines.Continuation";

  /**
   * The JVM return type of a Kotlin function declared {@code Unit?}, or with an expression body of
   * that type; matched by name, as Spring's {@code @Async} matches it.
   */
  private static final String KOTLIN_UNIT = "kotlin.Unit";

  /** Spring MVC's types, matched by name like the publisher, so Spring MVC need not be present. */
  private static final String WEB_ASYNC_TASK =
      "org.springframework.web.context.request.async.WebAsyncTask";

  private static final String DEFERRED_RESULT =
      "org.springframework.web.context.request.async.DeferredResult";

  private static final String STREAMING_RESPONSE_BODY =
      "org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody";

  private static final String RESPONSE_BODY_EMITTER =
      "org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter";

  private static final String RESPONSE_ENTITY = "org.springframework.http.ResponseEntity";

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
   * reactive return type, a Kotlin suspend function, a type Spring MVC runs after the method
   * returns, and a {@link Future} or {@link CompletionStage}, unless Spring's {@code @Async} runs
   * the whole method under the lock on its worker thread. Also rejects an {@code @Async} method
   * that returns anything but {@code void}, Kotlin's {@code Unit}, {@link Future} or {@link
   * CompletableFuture}, the only types Spring's {@code @Async} can return: any other type fails
   * every call.
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
    // Spring's reactive adapter registry adapts CompletionStage too, which the @Async rule judges.
    if (isReactive(returnType)
        || (!CompletionStage.class.isAssignableFrom(returnType) && isAdapted(returnType))) {
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
    } else if (runsAfterReturn(method)) {
      throw error(
          type,
          method,
          "return type "
              + method.getGenericReturnType().getTypeName()
              + " runs its work after the lock is released, which happens when the method"
              + " returns; put the annotation on the method that does the work, such as a"
              + " service method called inside the Callable");
    }
  }

  /**
   * Whether Spring MVC, by the rules of its return value handlers, runs the work of a handler with
   * this method's return type after the handler returns: a {@link Callable}, {@code WebAsyncTask},
   * {@code DeferredResult}, {@code StreamingResponseBody} or {@code ResponseBodyEmitter}, and a
   * {@code ResponseEntity} of a {@code StreamingResponseBody}, an emitter or a type the reactive
   * adapter registry adapts. Spring MVC also runs a returned {@link CompletionStage} later, which
   * the {@code @Async} rule judges.
   */
  private static boolean runsAfterReturn(@NonNull Method method) {
    Class<?> type = method.getReturnType();
    if (Callable.class.isAssignableFrom(type)
        || isSubtypeOf(type, WEB_ASYNC_TASK)
        || isSubtypeOf(type, DEFERRED_RESULT)
        || isStreamed(type)) {
      return true;
    }
    if (!isSubtypeOf(type, RESPONSE_ENTITY)) {
      return false;
    }
    Class<?> body = ResolvableType.forMethodReturnType(method).getGeneric().resolve();
    return body != null && (isStreamed(body) || isAdapted(body));
  }

  /** A {@code StreamingResponseBody}, or a {@code ResponseBodyEmitter} such as an SSE emitter. */
  private static boolean isStreamed(@NonNull Class<?> type) {
    return isSubtypeOf(type, STREAMING_RESPONSE_BODY) || isSubtypeOf(type, RESPONSE_BODY_EMITTER);
  }

  /** Whether the shared registry Spring MVC consults adapts the type to a reactive publisher. */
  private static boolean isAdapted(@NonNull Class<?> type) {
    return ReactiveAdapterRegistry.getSharedInstance().getAdapter(type) != null;
  }

  /** Whether the type is, extends or implements the type of that name. */
  private static boolean isSubtypeOf(@Nullable Class<?> type, @NonNull String name) {
    if (type == null) {
      return false;
    }
    if (type.getName().equals(name) || isSubtypeOf(type.getSuperclass(), name)) {
      return true;
    }
    for (Class<?> parent : type.getInterfaces()) {
      if (isSubtypeOf(parent, name)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Names a value an annotated method returned when work behind it is still pending, so that the
   * work runs after the lock and permit are released; null for any other value. The startup check
   * judges the declared return type, which a wider type such as {@code Object} or {@code
   * ResponseEntity<?>} hides; this judges the value with the same rules, and a {@code
   * ResponseEntity} by the class of its body, as Spring MVC does. A completed future, which is what
   * an {@code @Async} method body returns, is not pending.
   *
   * @param value what the method returned
   * @return the value's type, or null when no work is pending
   */
  static @Nullable String pendingWork(@Nullable Object value) {
    if (value == null) {
      return null;
    }
    if (isPending(value)) {
      return nameOf(value.getClass());
    }
    if (isSubtypeOf(value.getClass(), RESPONSE_ENTITY)) {
      Method getBody = ReflectionUtils.findMethod(value.getClass(), "getBody");
      Object body = getBody == null ? null : ReflectionUtils.invokeMethod(getBody, value);
      if (body != null
          && (isSubtypeOf(body.getClass(), RESPONSE_BODY_EMITTER) || isAdapted(body.getClass()))
          && isPending(body)) {
        return value.getClass().getName() + " of " + nameOf(body.getClass());
      }
    }
    return null;
  }

  private static boolean isPending(@NonNull Object value) {
    if (value instanceof CompletionStage<?> stage) {
      // Not isDone: the JDK's minimal stage is a Future whose isDone throws.
      try {
        return !stage.toCompletableFuture().isDone();
      } catch (UnsupportedOperationException e) {
        return true;
      }
    }
    if (value instanceof Future<?> future) {
      return !future.isDone();
    }
    Class<?> type = value.getClass();
    return value instanceof Callable<?>
        || isReactive(type)
        || isAdapted(type)
        || isSubtypeOf(type, WEB_ASYNC_TASK)
        || isSubtypeOf(type, DEFERRED_RESULT)
        || isStreamed(type);
  }

  /** The class name, or for a lambda the interface it implements. */
  private static @NonNull String nameOf(@NonNull Class<?> type) {
    return type.isHidden() && type.getInterfaces().length > 0
        ? type.getInterfaces()[0].getName()
        : type.getName();
  }

  private static boolean isReactive(@Nullable Class<?> type) {
    if (type == null) {
      return false;
    }
    if (Flow.Publisher.class.isAssignableFrom(type)
        || type.getName().equals(REACTIVE_STREAMS_PUBLISHER)
        || type.getName().equals(KOTLIN_FLOW)
        || type.getName().equals(KOTLIN_DEFERRED)
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
