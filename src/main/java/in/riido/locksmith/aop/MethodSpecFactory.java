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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.aop.scope.ScopedProxyUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ListableBeanFactory;
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

  /** Kotlin's handle to a launched coroutine, which runs on after the call; matched by name. */
  private static final String KOTLIN_JOB = "kotlinx.coroutines.Job";

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

  /**
   * The responses of Spring MVC's functional endpoints whose work runs after the handler returns,
   * matched by name like the others; the SSE and streaming ones are not public.
   */
  private static final String ASYNC_SERVER_RESPONSE =
      "org.springframework.web.servlet.function.AsyncServerResponse";

  private static final String SSE_SERVER_RESPONSE =
      "org.springframework.web.servlet.function.SseServerResponse";

  private static final String STREAMING_SERVER_RESPONSE =
      "org.springframework.web.servlet.function.StreamingServerResponse";

  /** What Spring makes of an async response whose future is already done; not public either. */
  private static final String COMPLETED_ASYNC_SERVER_RESPONSE =
      "org.springframework.web.servlet.function.CompletedAsyncServerResponse";

  /** A functional endpoint's response with a body, written later when the body is not ready. */
  private static final String ENTITY_RESPONSE =
      "org.springframework.web.servlet.function.EntityResponse";

  /** Whether a value of a class can be pending work at all; judged once per class. */
  private static final ClassValue<Boolean> MAY_BE_PENDING =
      new ClassValue<>() {
        @Override
        protected @NonNull Boolean computeValue(@NonNull Class<?> type) {
          return CompletionStage.class.isAssignableFrom(type)
              || Future.class.isAssignableFrom(type)
              || Callable.class.isAssignableFrom(type)
              || isSubtypeOf(type, KOTLIN_JOB)
              || isSubtypeOf(type, ASYNC_SERVER_RESPONSE)
              || isSubtypeOf(type, SSE_SERVER_RESPONSE)
              || isSubtypeOf(type, STREAMING_SERVER_RESPONSE)
              || isSubtypeOf(type, RESPONSE_ENTITY)
              || isSubtypeOf(type, ENTITY_RESPONSE)
              || isSubtypeOf(type, WEB_ASYNC_TASK)
              || isSubtypeOf(type, DEFERRED_RESULT)
              || isStreamed(type)
              || isReactive(type)
              || isAdapted(type);
        }
      };

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
   * Names the failure handler beans of a handler type, the rule by which the startup check counts
   * them and the interceptor calls one. A bean of the handler interface matches when its class is
   * the handler type: the class Spring predicts, or, for a bean whose type is a JDK proxy class, as
   * an advised handler is under interface-based proxying, the class behind the proxy. The hidden
   * target of a scoped proxy is skipped, as Spring's own lookup by type skips it. Only a bean whose
   * type is a JDK proxy class is fetched, to look behind it.
   *
   * @param beans the bean factory
   * @param handlerType the handler type an annotation names
   * @param handlerInterface {@code LockFailureHandler} or {@code SemaphoreFailureHandler}
   * @return the names of the matching beans
   */
  public static @NonNull List<String> failureHandlerNames(
      @NonNull ListableBeanFactory beans,
      @NonNull Class<?> handlerType,
      @NonNull Class<?> handlerInterface) {
    List<String> names = new ArrayList<>();
    for (String name : beans.getBeanNamesForType(handlerInterface)) {
      if (ScopedProxyUtils.isScopedTarget(name)) {
        continue;
      }
      Class<?> type = beans.getType(name);
      if (type != null && Proxy.isProxyClass(type)) {
        type = AopUtils.getTargetClass(beans.getBean(name));
      }
      if (type != null && handlerType.isAssignableFrom(type)) {
        names.add(name);
      }
    }
    return names;
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
   * @throws LocksmithConfigurationException if the proxy inherits the method from two unrelated
   *     interfaces that declare it with different Locksmith annotations, since only one declaration
   *     is read
   */
  public static @NonNull Method interfaceMethodOfJdkProxy(@NonNull Method method) {
    Class<?> proxyClass = method.getDeclaringClass();
    if (!Proxy.isProxyClass(proxyClass)) {
      return method;
    }
    // getMethods, not getMethod: an interface can inherit one method from two unrelated
    // interfaces, and getMethod returns only the first, which may lack the annotation.
    List<Method> annotated = new ArrayList<>();
    for (Class<?> type : proxyClass.getInterfaces()) {
      for (Method candidate : type.getMethods()) {
        if (candidate.getName().equals(method.getName())
            && Arrays.equals(candidate.getParameterTypes(), method.getParameterTypes())
            && isAnnotated(candidate)
            && !annotated.contains(candidate)) {
          annotated.add(candidate);
        }
      }
    }
    // A declaration in a subinterface already carries the annotations of the one it overrides.
    List<Method> declarations =
        annotated.stream()
            .filter(
                parent ->
                    annotated.stream()
                        .noneMatch(
                            child ->
                                child != parent
                                    && parent
                                        .getDeclaringClass()
                                        .isAssignableFrom(child.getDeclaringClass())))
            .toList();
    // Declarations that carry the same annotations give the same spec, whichever is read.
    if (declarations.stream().map(MethodSpecFactory::annotationsOf).distinct().count() > 1) {
      throw new LocksmithConfigurationException(
          "Locksmith annotations on "
              + declarations.stream()
                  .map(d -> d.getDeclaringClass().getName() + "." + d.getName() + " " + names(d))
                  .collect(Collectors.joining(" and "))
              + ": a JDK proxy bean inherits "
              + method.getName()
              + " from each of these interfaces, and Locksmith reads one declaration of a method,"
              + " so the others would be ignored; put all the annotations on one declaration, such"
              + " as "
              + method.getName()
              + " declared again in the interface that extends them");
    }
    return declarations.isEmpty() ? method : declarations.get(0);
  }

  /** The Locksmith annotations of a method declaration, null where it has none, to compare. */
  private static @NonNull List<Annotation> annotationsOf(@NonNull Method method) {
    return Arrays.asList(
        AnnotatedElementUtils.findMergedAnnotation(method, DistributedLock.class),
        AnnotatedElementUtils.findMergedAnnotation(method, DistributedSemaphore.class));
  }

  /** The Locksmith annotations a method declaration carries, as a message names them. */
  private static @NonNull String names(@NonNull Method method) {
    boolean lock =
        AnnotatedElementUtils.findMergedAnnotation(method, DistributedLock.class) != null;
    boolean semaphore =
        AnnotatedElementUtils.findMergedAnnotation(method, DistributedSemaphore.class) != null;
    return lock && semaphore
        ? "(@DistributedLock and @DistributedSemaphore)"
        : lock ? "(@DistributedLock)" : "(@DistributedSemaphore)";
  }

  /**
   * Builds the spec of a method. A method without either annotation gets a spec with both parts
   * null. A type variable in the return type is read as the declaring class leaves it.
   *
   * @param method the annotated method
   * @return the spec
   * @throws LocksmithConfigurationException on the first rule the annotations break, naming the
   *     class, the method and the offending attribute and value
   */
  public @NonNull MethodSpec create(@NonNull Method method) {
    return create(method, method.getDeclaringClass());
  }

  /**
   * Builds the spec of a method as it runs on a bean of the given class. The return type is read as
   * Spring MVC reads it, with the type variables that class binds, so the {@code ResponseEntity<T>}
   * of a generic base class is judged by what {@code T} is on that bean.
   *
   * @param method the annotated method
   * @param beanClass the class of the bean the method runs on
   * @return the spec
   * @throws LocksmithConfigurationException on the first rule the annotations break, naming the
   *     class, the method and the offending attribute and value
   */
  public @NonNull MethodSpec create(@NonNull Method method, @NonNull Class<?> beanClass) {
    DistributedLock lock =
        AnnotatedElementUtils.findMergedAnnotation(method, DistributedLock.class);
    DistributedSemaphore semaphore =
        AnnotatedElementUtils.findMergedAnnotation(method, DistributedSemaphore.class);
    return new MethodSpec(
        lock == null ? null : lockSpec(lock, method, beanClass),
        semaphore == null ? null : semaphoreSpec(semaphore, method, beanClass));
  }

  private static @NonNull LockSpec lockSpec(
      @NonNull DistributedLock annotation, @NonNull Method method, @NonNull Class<?> beanClass) {
    Class<DistributedLock> type = DistributedLock.class;
    checkInterceptable(type, method);
    Expression key = parseKey(type, annotation.key(), method);
    Duration waitTime = parseWaitTime(type, annotation.waitTime(), method);
    Duration leaseTime = parseLeaseTime(type, annotation.leaseTime(), method);
    Class<? extends LockFailureHandler> handler =
        annotation.handler() == LockFailureHandler.class ? null : annotation.handler();
    checkHandler(type, annotation.onFailure(), handler, LockFailureHandler.class, method);
    checkReturnType(type, method, beanClass);
    return new LockSpec(
        key, annotation.type(), waitTime, leaseTime, annotation.onFailure(), handler);
  }

  private @NonNull SemaphoreSpec semaphoreSpec(
      @NonNull DistributedSemaphore annotation,
      @NonNull Method method,
      @NonNull Class<?> beanClass) {
    Class<DistributedSemaphore> type = DistributedSemaphore.class;
    checkInterceptable(type, method);
    Expression key = parseKey(type, annotation.key(), method);
    int permits = parsePermits(annotation.permits(), method);
    Duration waitTime = parseWaitTime(type, annotation.waitTime(), method);
    Duration leaseTime = parseLeaseTime(type, annotation.leaseTime(), method);
    Class<? extends SemaphoreFailureHandler> handler =
        annotation.handler() == SemaphoreFailureHandler.class ? null : annotation.handler();
    checkHandler(type, annotation.onFailure(), handler, SemaphoreFailureHandler.class, method);
    checkReturnType(type, method, beanClass);
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
   * returns, such as a functional endpoint's {@code EntityResponse} of a {@link CompletionStage} or
   * a reactive type, and a {@link Future}, a {@link CompletionStage} or a Kotlin {@code Job},
   * unless Spring's {@code @Async} runs the whole method under the lock on its worker thread. Also
   * rejects an {@code @Async} method that returns anything but {@code void}, Kotlin's {@code Unit},
   * {@link Future} or {@link CompletableFuture}, the only types Spring's {@code @Async} can return:
   * any other type fails every call. The return type is read with the type variables the bean class
   * binds, as Spring MVC reads it; the {@code @Async} rule reads it erased, as {@code @Async} does.
   */
  private static void checkReturnType(
      @NonNull Class<? extends Annotation> type,
      @NonNull Method method,
      @NonNull Class<?> beanClass) {
    Class<?>[] parameters = method.getParameterTypes();
    if (parameters.length > 0
        && parameters[parameters.length - 1].getName().equals(KOTLIN_CONTINUATION)) {
      throw error(
          type,
          method,
          "Kotlin suspend functions are not supported; use a function that is not suspend");
    }
    ResolvableType declared = ResolvableType.forMethodReturnType(method, beanClass);
    Class<?> returnType = declared.toClass();
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
    if (isAsync(method, beanClass)) {
      Class<?> erased = method.getReturnType();
      if (erased != void.class
          && !erased.getName().equals(KOTLIN_UNIT)
          && erased != Future.class
          && erased != CompletableFuture.class) {
        throw error(
            type,
            method,
            "@Async methods can return only void, Kotlin Unit, Future or CompletableFuture, got ["
                + erased.getName()
                + "]; Spring's @Async proxy fails every call of any other return type");
      }
    } else if (Future.class.isAssignableFrom(returnType)
        || CompletionStage.class.isAssignableFrom(returnType)
        || isSubtypeOf(returnType, KOTLIN_JOB)) {
      throw error(
          type,
          method,
          "return type "
              + returnType.getName()
              + " would let the work outlive the lock, which is released when the method returns;"
              + " mark the method @Async and declare Future or CompletableFuture, so the lock"
              + " covers its body on the worker thread");
    } else if (runsAfterReturn(declared)) {
      throw error(
          type,
          method,
          "return type "
              + declared
              + " runs its work after the lock is released, which happens when the method"
              + " returns; put the annotation on the method that does the work");
    }
  }

  /**
   * Whether Spring MVC, by the rules of its return value handlers, runs the work of a handler with
   * this return type after the handler returns: a {@link Callable}, {@code WebAsyncTask}, {@code
   * DeferredResult}, {@code StreamingResponseBody}, {@code ResponseBodyEmitter} or a functional
   * endpoint's {@code AsyncServerResponse}, a {@code ResponseEntity}, or a subclass of it, of a
   * {@code StreamingResponseBody}, an emitter or a type the reactive adapter registry adapts, and a
   * functional endpoint's {@code EntityResponse}, or a subtype that binds it, of a {@link
   * CompletionStage} or a type the registry adapts. Spring MVC also runs a returned {@link
   * CompletionStage} later, which the {@code @Async} rule judges.
   */
  private static boolean runsAfterReturn(@NonNull ResolvableType declared) {
    Class<?> type = declared.toClass();
    if (Callable.class.isAssignableFrom(type)
        || isSubtypeOf(type, WEB_ASYNC_TASK)
        || isSubtypeOf(type, DEFERRED_RESULT)
        || isStreamed(type)
        || isSubtypeOf(type, ASYNC_SERVER_RESPONSE)) {
      return true;
    }
    Class<?> body = entityBody(declared, RESPONSE_ENTITY);
    if (body != null && (isStreamed(body) || isAdapted(body))) {
      return true;
    }
    Class<?> entity = entityBody(declared, ENTITY_RESPONSE);
    return entity != null && (CompletionStage.class.isAssignableFrom(entity) || isAdapted(entity));
  }

  /**
   * The body type of the {@code ResponseEntity} or {@code EntityResponse} of that name, or of a
   * subtype that binds it; else null.
   */
  private static @Nullable Class<?> entityBody(
      @NonNull ResolvableType declared, @NonNull String name) {
    Class<?> entity = supertypeNamed(declared.toClass(), name);
    return entity == null ? null : declared.as(entity).getGeneric().resolve();
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
    return supertypeNamed(type, name) != null;
  }

  /** The class or interface of that name that the type is, extends or implements; else null. */
  private static @Nullable Class<?> supertypeNamed(@Nullable Class<?> type, @NonNull String name) {
    if (type == null || type.getName().equals(name)) {
      return type;
    }
    Class<?> found = supertypeNamed(type.getSuperclass(), name);
    if (found != null) {
      return found;
    }
    for (Class<?> parent : type.getInterfaces()) {
      found = supertypeNamed(parent, name);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  /**
   * Names a value an annotated method returned when work behind it is still pending, or its
   * completion cannot be checked, so it may run after the lock and permit are released; null for
   * any other value. The startup check judges the declared return type, which a wider type such as
   * {@code Object} or {@code ResponseEntity<?>} hides; this judges the value with the same rules, a
   * {@code ResponseEntity} by the class of its body and a functional endpoint's {@code
   * EntityResponse} by the class of its entity, as Spring MVC does. A completed future, which is
   * what an {@code @Async} method body returns, is not pending, nor is a completed Kotlin {@code
   * Job}.
   *
   * @param value what the method returned
   * @return the value's type, or null when no work is pending
   */
  static @Nullable String pendingWork(@Nullable Object value) {
    if (value == null || !MAY_BE_PENDING.get(value.getClass())) {
      return null;
    }
    if (isPending(value)) {
      return nameOf(value.getClass());
    }
    Class<?> entity = supertypeNamed(value.getClass(), RESPONSE_ENTITY);
    if (entity != null) {
      // Looked up on Spring's public class: a subclass that overrides getBody may not be public.
      Method getBody = ReflectionUtils.findMethod(entity, "getBody");
      Object body = getBody == null ? null : ReflectionUtils.invokeMethod(getBody, value);
      if (body != null
          && (isSubtypeOf(body.getClass(), RESPONSE_BODY_EMITTER) || isAdapted(body.getClass()))
          && isPending(body)) {
        return value.getClass().getName() + " of " + nameOf(body.getClass());
      }
    }
    Class<?> response = supertypeNamed(value.getClass(), ENTITY_RESPONSE);
    if (response != null) {
      // Asked through the public interface, since Spring's implementations are not public.
      Object body =
          ReflectionUtils.invokeMethod(ReflectionUtils.findMethod(response, "entity"), value);
      if (body != null
          && (body instanceof CompletionStage<?> || isAdapted(body.getClass()))
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
    Class<?> job = supertypeNamed(type, KOTLIN_JOB);
    if (job != null) {
      // A Kotlin Job, a Deferred too, is pending until it completes. Asked through the public
      // interface, since the class of a launched coroutine is not public.
      return !Boolean.TRUE.equals(
          ReflectionUtils.invokeMethod(ReflectionUtils.findMethod(job, "isCompleted"), value));
    }
    Class<?> async = supertypeNamed(type, ASYNC_SERVER_RESPONSE);
    if (async != null) {
      if (!type.getName().equals(COMPLETED_ASYNC_SERVER_RESPONSE)) {
        return true;
      }
      // An async response whose future was already done: block() returns the finished response
      // at once, and that response is what Spring writes.
      Object done = ReflectionUtils.invokeMethod(ReflectionUtils.findMethod(async, "block"), value);
      return done != null && isPending(done);
    }
    return value instanceof Callable<?>
        || isReactive(type)
        || isAdapted(type)
        || isSubtypeOf(type, WEB_ASYNC_TASK)
        || isSubtypeOf(type, DEFERRED_RESULT)
        || isStreamed(type)
        || isSubtypeOf(type, SSE_SERVER_RESPONSE)
        || isSubtypeOf(type, STREAMING_SERVER_RESPONSE);
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

  private static boolean isAsync(@NonNull Method method, @NonNull Class<?> beanClass) {
    return AnnotatedElementUtils.hasAnnotation(method, Async.class)
        || AnnotatedElementUtils.hasAnnotation(beanClass, Async.class);
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
