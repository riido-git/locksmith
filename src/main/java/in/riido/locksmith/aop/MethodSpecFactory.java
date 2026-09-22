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
import java.time.Duration;
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
    Expression key = parseKey(type, annotation.key(), method);
    Duration waitTime = parseWaitTime(type, annotation.waitTime(), method);
    Duration leaseTime = parseLeaseTime(type, annotation.leaseTime(), method);
    Class<? extends LockFailureHandler> handler =
        annotation.handler() == LockFailureHandler.class ? null : annotation.handler();
    checkHandler(type, annotation.onFailure(), handler, LockFailureHandler.class, method);
    checkReturnType(type, annotation.onFailure(), method);
    return new LockSpec(
        key, annotation.type(), waitTime, leaseTime, annotation.onFailure(), handler);
  }

  private @NonNull SemaphoreSpec semaphoreSpec(
      @NonNull DistributedSemaphore annotation, @NonNull Method method) {
    Class<DistributedSemaphore> type = DistributedSemaphore.class;
    Expression key = parseKey(type, annotation.key(), method);
    int permits = parsePermits(annotation.permits(), method);
    Duration waitTime = parseWaitTime(type, annotation.waitTime(), method);
    Duration leaseTime = parseLeaseTime(type, annotation.leaseTime(), method);
    Class<? extends SemaphoreFailureHandler> handler =
        annotation.handler() == SemaphoreFailureHandler.class ? null : annotation.handler();
    checkHandler(type, annotation.onFailure(), handler, SemaphoreFailureHandler.class, method);
    checkReturnType(type, annotation.onFailure(), method);
    return new SemaphoreSpec(
        key,
        permits,
        waitTime,
        leaseTime == null ? properties.semaphore().leaseTime() : leaseTime,
        annotation.onFailure(),
        handler);
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
   * Rejects a method whose completion the interceptor cannot follow: a reactive return type, a
   * Kotlin suspend function, or a {@link Future} that is not a {@link CompletionStage}, unless
   * Spring's {@code @Async} runs the whole method under the lock on its worker thread. Also rejects
   * {@code SKIP} when the return type cannot hold the {@link CompletableFuture} it returns.
   */
  private static void checkReturnType(
      @NonNull Class<? extends Annotation> type,
      @NonNull OnFailure onFailure,
      @NonNull Method method) {
    Class<?> returnType = method.getReturnType();
    Class<?>[] parameters = method.getParameterTypes();
    if (parameters.length > 0
        && parameters[parameters.length - 1].getName().equals(KOTLIN_CONTINUATION)) {
      throw error(
          type,
          method,
          "Kotlin suspend functions are not supported; return CompletableFuture from a function"
              + " that is not suspend");
    }
    if (isReactive(returnType)) {
      throw error(
          type,
          method,
          "reactive return types are not supported, got ["
              + returnType.getName()
              + "]; return CompletableFuture or CompletionStage");
    }
    boolean stage = CompletionStage.class.isAssignableFrom(returnType);
    if (Future.class.isAssignableFrom(returnType) && !stage && !isAsync(method)) {
      throw error(
          type,
          method,
          "return type "
              + returnType.getName()
              + " cannot be tracked to completion; return CompletableFuture, or mark the method"
              + " @Async");
    }
    if (onFailure == OnFailure.SKIP
        && stage
        && !returnType.isAssignableFrom(CompletableFuture.class)) {
      throw error(
          type,
          method,
          "onFailure SKIP returns a CompletableFuture, which return type ["
              + returnType.getName()
              + "] cannot hold; declare CompletableFuture or CompletionStage, or use onFailure"
              + " HANDLER");
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
