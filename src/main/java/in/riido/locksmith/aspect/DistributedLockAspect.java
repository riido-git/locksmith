package in.riido.locksmith.aspect;

import in.riido.locksmith.DistributedLock;
import in.riido.locksmith.LeaseExpirationBehavior;
import in.riido.locksmith.LockAcquisitionMode;
import in.riido.locksmith.LockType;
import in.riido.locksmith.autoconfigure.LocksmithProperties;
import in.riido.locksmith.exception.LeaseExpiredException;
import in.riido.locksmith.handler.LockContext;
import in.riido.locksmith.handler.LockSkipHandler;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.Ordered;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.annotation.Order;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;

/**
 * Aspect that handles distributed locking for methods annotated with {@link DistributedLock}. Uses
 * Redisson's RLock implementation for distributed lock management across multiple server instances.
 *
 * <p>This aspect is ordered with {@link Ordered#HIGHEST_PRECEDENCE} to ensure the lock is acquired
 * before any transaction starts.
 *
 * @author Garvit Joshi
 * @since 1.0.0
 */
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DistributedLockAspect {

  private static final Logger LOG = LoggerFactory.getLogger(DistributedLockAspect.class);
  private static final ExpressionParser EXPRESSION_PARSER = new SpelExpressionParser();
  private static final ParameterNameDiscoverer PARAMETER_NAME_DISCOVERER =
      new DefaultParameterNameDiscoverer();
  private static final Map<Class<? extends LockSkipHandler>, LockSkipHandler> HANDLER_CACHE =
      new ConcurrentHashMap<>(5);
  private static final Map<String, Expression> EXPRESSION_CACHE = new ConcurrentHashMap<>();

  private final RedissonClient redissonClient;
  private final LocksmithProperties lockProperties;

  /**
   * Constructs a new DistributedLockAspect.
   *
   * @param redissonClient the Redisson client for Redis operations
   * @param lockProperties the configuration properties
   */
  public DistributedLockAspect(RedissonClient redissonClient, LocksmithProperties lockProperties) {
    this.redissonClient = redissonClient;
    this.lockProperties = lockProperties;
  }

  /**
   * Around advice that handles the distributed lock lifecycle for annotated methods.
   *
   * @param joinPoint the join point representing the intercepted method
   * @return the result of the method execution, or a default value if skipped
   * @throws Throwable if the method execution throws an exception
   */
  @Around("@annotation(in.riido.locksmith.DistributedLock)")
  public Object handleDistributedLock(ProceedingJoinPoint joinPoint) throws Throwable {
    final MethodSignature signature = (MethodSignature) joinPoint.getSignature();
    final DistributedLock distributedLock =
        signature.getMethod().getAnnotation(DistributedLock.class);
    final boolean debugMode = Boolean.TRUE.equals(lockProperties.debug());
    final String methodName = formatMethodSignature(joinPoint);

    if (distributedLock.key().isBlank()) {
      throw new IllegalArgumentException(
          "DistributedLock key must not be blank on method: "
              + signature.getDeclaringType().getName()
              + "."
              + signature.getName());
    }

    final String resolvedKey = resolveKey(distributedLock.key(), signature.getMethod(), joinPoint);
    final String lockKey = lockProperties.keyPrefix() + resolvedKey;
    final RLock lock = getLock(lockKey, distributedLock.type());
    final boolean autoRenew = distributedLock.autoRenew();

    // Log warnings for conflicting settings when autoRenew is enabled
    if (autoRenew) {
      if (!distributedLock.leaseTime().isBlank()) {
        LOG.warn(
            "autoRenew is enabled for [{}] but leaseTime is also specified. "
                + "leaseTime will be ignored as Redisson's watchdog will manage lease renewal.",
            methodName);
      }
      if (distributedLock.onLeaseExpired() != LeaseExpirationBehavior.IGNORE) {
        LOG.warn(
            "autoRenew is enabled for [{}] but onLeaseExpired is set to {}. "
                + "This setting will have no effect as the lock will never expire during execution.",
            methodName,
            distributedLock.onLeaseExpired());
      }
    }

    final Duration leaseTime =
        autoRenew
            ? Duration.ofMillis(-1)
            : resolveDuration(distributedLock.leaseTime(), lockProperties.leaseTime());
    final Duration waitTime =
        resolveDuration(distributedLock.waitTime(), lockProperties.waitTime());

    if (debugMode) {
      LOG.info(
          "Acquiring lock [{}] for [{}] - type={}, mode={}, leaseTime={}, waitTime={}, autoRenew={}",
          lockKey,
          methodName,
          distributedLock.type(),
          distributedLock.mode(),
          leaseTime,
          waitTime,
          autoRenew);
    }

    boolean lockAcquired = false;

    try {
      lockAcquired = tryAcquireLock(lock, distributedLock.mode(), waitTime, leaseTime);

      if (!lockAcquired) {
        if (debugMode) {
          LOG.info(
              "Lock acquisition failed for [{}] in [{}], invoking skip handler: {}",
              lockKey,
              methodName,
              distributedLock.skipHandler().getSimpleName());
        } else {
          LOG.info(
              "Skipping execution of [{}] - lock [{}] is held by another instance",
              methodName,
              lockKey);
        }
        return handleSkip(distributedLock, joinPoint, lockKey, methodName);
      }

      LOG.info("Lock [{}] acquired for [{}]", lockKey, methodName);

      final long startTime = System.currentTimeMillis();
      final Object result = joinPoint.proceed();
      final long executionTime = System.currentTimeMillis() - startTime;

      if (debugMode) {
        LOG.info(
            "Method [{}] executed in {}ms, returnType={}, hasResult={}",
            methodName,
            executionTime,
            signature.getReturnType().getSimpleName(),
            result != null);
      }

      // Skip lease expiration check when autoRenew is enabled
      if (!autoRenew) {
        checkLeaseExpiration(
            distributedLock.onLeaseExpired(), leaseTime, executionTime, lockKey, methodName);
      }

      return result;

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOG.warn("Thread interrupted while waiting for lock [{}] in [{}]", lockKey, methodName);
      return handleSkip(distributedLock, joinPoint, lockKey, methodName);
    } finally {
      if (lockAcquired) {
        releaseLock(lock, lockKey, methodName);
      }
    }
  }

  /**
   * Checks if the method execution time exceeded the lease duration and handles accordingly.
   *
   * @param behavior the configured behavior for lease expiration
   * @param leaseTime the configured lease duration
   * @param executionTimeMs the actual execution time in milliseconds
   * @param lockKey the lock key
   * @param methodName the method name
   */
  private void checkLeaseExpiration(
      LeaseExpirationBehavior behavior,
      Duration leaseTime,
      long executionTimeMs,
      String lockKey,
      String methodName) {

    final long leaseTimeMs = leaseTime.toMillis();

    if (executionTimeMs <= leaseTimeMs) {
      return;
    }

    switch (behavior) {
      case LOG_WARNING ->
          LOG.warn(
              "Lock [{}] lease may have expired during execution of [{}]. "
                  + "Lease time: {}ms, Execution time: {}ms. "
                  + "Consider increasing the lease time.",
              lockKey,
              methodName,
              leaseTimeMs,
              executionTimeMs);
      case THROW_EXCEPTION ->
          throw new LeaseExpiredException(lockKey, methodName, leaseTimeMs, executionTimeMs);
      case IGNORE -> {
        // Do nothing
      }
    }
  }

  private void releaseLock(RLock lock, String lockKey, String methodName) {
    try {
      lock.unlock();
      LOG.info("Lock [{}] released for [{}]", lockKey, methodName);
    } catch (IllegalMonitorStateException e) {
      // Lock may have expired or been released due to virtual thread carrier thread changes
      LOG.warn(
          "Lock [{}] was already released (possibly expired) for [{}]: {}",
          lockKey,
          methodName,
          e.getMessage());
    }
  }

  /**
   * Gets the appropriate lock based on the lock type.
   *
   * @param lockKey the key for the lock
   * @param lockType the type of lock to acquire
   * @return the appropriate RLock instance
   */
  private RLock getLock(String lockKey, LockType lockType) {
    return switch (lockType) {
      case REENTRANT -> redissonClient.getLock(lockKey);
      case READ -> redissonClient.getReadWriteLock(lockKey).readLock();
      case WRITE -> redissonClient.getReadWriteLock(lockKey).writeLock();
    };
  }

  private boolean tryAcquireLock(
      RLock lock, LockAcquisitionMode mode, Duration waitTime, Duration leaseTime)
      throws InterruptedException {
    final long leaseTimeMs = leaseTime.toMillis();
    final long waitTimeMs = waitTime.toMillis();
    return switch (mode) {
      case SKIP_IMMEDIATELY -> lock.tryLock(0, leaseTimeMs, TimeUnit.MILLISECONDS);
      case WAIT_AND_SKIP -> lock.tryLock(waitTimeMs, leaseTimeMs, TimeUnit.MILLISECONDS);
    };
  }

  private String formatMethodSignature(ProceedingJoinPoint joinPoint) {
    final MethodSignature signature = (MethodSignature) joinPoint.getSignature();
    return signature.getDeclaringType().getSimpleName() + "." + signature.getName();
  }

  /**
   * Gets a cached instance of the specified handler class, creating it if necessary.
   *
   * <p>This method provides thread-safe caching of handler instances to avoid the overhead of
   * reflection-based instantiation on every lock skip. Handler instances are cached per class type
   * and reused across all invocations.
   *
   * <p><b>Important:</b> Handler classes must be stateless and thread-safe, as a single instance
   * will be shared across all concurrent invocations.
   *
   * @param handlerClass the handler class to instantiate
   * @return a cached or newly created instance of the handler
   * @throws IllegalStateException if the handler cannot be instantiated
   */
  private LockSkipHandler getHandlerInstance(Class<? extends LockSkipHandler> handlerClass) {
    return HANDLER_CACHE.computeIfAbsent(
        handlerClass,
        clazz -> {
          try {
            return clazz.getDeclaredConstructor().newInstance();
          } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                "Failed to instantiate skip handler: "
                    + clazz.getName()
                    + ". Ensure it has a public no-argument constructor.",
                e);
          }
        });
  }

  private Object handleSkip(
      DistributedLock annotation,
      ProceedingJoinPoint joinPoint,
      String lockKey,
      String methodName) {
    final LockSkipHandler handler = getHandlerInstance(annotation.skipHandler());
    final MethodSignature signature = (MethodSignature) joinPoint.getSignature();
    final LockContext context =
        new LockContext(
            lockKey,
            methodName,
            signature.getMethod(),
            joinPoint.getArgs(),
            signature.getReturnType());
    return handler.handle(context);
  }

  /**
   * Resolves a duration from the given string, falling back to the default if blank.
   *
   * @param durationString the duration string (e.g., "10m", "30s", "PT10M")
   * @param defaultValue the default value to use if the string is blank
   * @return the resolved Duration, or the default value if the string is blank
   * @throws IllegalArgumentException if the value is not a known style or cannot be * parsed
   */
  private Duration resolveDuration(String durationString, Duration defaultValue) {
    if (durationString == null || durationString.isBlank()) {
      return defaultValue;
    }
    return DurationStyle.detectAndParse(durationString);
  }

  /**
   * Resolves the lock key, evaluating SpEL expressions if present.
   *
   * <p>SpEL expressions must be wrapped in <code>#{...}</code> syntax: <code>#{#userId}</code>,
   * <code>#{'user-' + #id}</code>
   *
   * <p>Literal keys (without SpEL) are returned as-is and can contain any characters including
   * <code>#</code>: <code>order#123</code>, <code>item-#1</code>, <code>task#</code>
   *
   * @param keyExpression the key expression (literal or SpEL)
   * @param method the method being invoked
   * @param joinPoint the join point for accessing method arguments
   * @return the resolved key string
   */
  public String resolveKey(String keyExpression, Method method, ProceedingJoinPoint joinPoint) {
    // Check for #{...} syntax for SpEL expressions
    if (keyExpression.startsWith("#{") && keyExpression.endsWith("}")) {
      return evaluateSpELExpression(
          keyExpression.substring(2, keyExpression.length() - 1), method, joinPoint);
    }

    // No SpEL detected - return as literal key
    return keyExpression;
  }

  /**
   * Evaluates a SpEL expression and returns the resolved key.
   *
   * <p>SpEL expressions are cached after first parse to avoid repeated parsing overhead. The cache
   * is bounded by the number of unique {@code @DistributedLock} annotations in the application.
   *
   * @param spELExpression the SpEL expression to evaluate (without #{} wrapper)
   * @param method the method being invoked
   * @param joinPoint the join point for accessing method arguments
   * @return the resolved key string
   * @throws IllegalArgumentException if the expression evaluates to null or blank
   */
  public String evaluateSpELExpression(
      String spELExpression, Method method, ProceedingJoinPoint joinPoint) {
    EvaluationContext context =
        new MethodBasedEvaluationContext(
            null, method, joinPoint.getArgs(), PARAMETER_NAME_DISCOVERER);

    // Cache parsed expressions to avoid repeated parsing overhead
    Expression expression =
        EXPRESSION_CACHE.computeIfAbsent(spELExpression, EXPRESSION_PARSER::parseExpression);

    Object result = expression.getValue(context);

    if (result == null) {
      throw new IllegalArgumentException(
          "SpEL expression '"
              + spELExpression
              + "' evaluated to null for method: "
              + formatMethodSignature(joinPoint));
    }

    String resolvedKey = result.toString();
    if (resolvedKey.isBlank()) {
      throw new IllegalArgumentException(
          "SpEL expression '"
              + spELExpression
              + "' evaluated to blank for method: "
              + formatMethodSignature(joinPoint));
    }

    return resolvedKey;
  }
}
