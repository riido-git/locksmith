package in.riido.locksmith.aspect;

import in.riido.locksmith.DistributedLock;
import in.riido.locksmith.LeaseExpirationBehavior;
import in.riido.locksmith.LockAcquisitionMode;
import in.riido.locksmith.LockType;
import in.riido.locksmith.SkipBehavior;
import in.riido.locksmith.autoconfigure.LocksmithProperties;
import in.riido.locksmith.exception.LeaseExpiredException;
import in.riido.locksmith.exception.LockNotAcquiredException;
import java.lang.reflect.Method;
import java.time.Duration;
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

    final Duration leaseTime =
        resolveDuration(distributedLock.leaseTime(), lockProperties.leaseTime());
    final Duration waitTime =
        resolveDuration(distributedLock.waitTime(), lockProperties.waitTime());

    final String methodName = formatMethodSignature(joinPoint);
    boolean lockAcquired = false;

    try {
      lockAcquired = tryAcquireLock(lock, distributedLock.mode(), waitTime, leaseTime);

      if (!lockAcquired) {
        LOG.info(
            "Skipping execution of [{}] - lock [{}] is held by another instance",
            methodName,
            lockKey);
        return handleSkip(distributedLock.onSkip(), joinPoint, lockKey, methodName);
      }

      LOG.debug("Lock [{}] acquired for [{}]", lockKey, methodName);

      final long startTime = System.currentTimeMillis();
      final Object result = joinPoint.proceed();
      final long executionTime = System.currentTimeMillis() - startTime;

      checkLeaseExpiration(
          distributedLock.onLeaseExpired(), leaseTime, executionTime, lockKey, methodName);

      return result;

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOG.warn("Thread interrupted while waiting for lock [{}] in [{}]", lockKey, methodName);
      return handleSkip(distributedLock.onSkip(), joinPoint, lockKey, methodName);
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
      if (lock.isHeldByCurrentThread()) {
        lock.unlock();
        LOG.debug("Lock [{}] released for [{}]", lockKey, methodName);
      }
    } catch (IllegalMonitorStateException e) {
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
    final long leaseTimeSeconds = leaseTime.toSeconds();
    final long waitTimeSeconds = waitTime.toSeconds();
    return switch (mode) {
      case SKIP_IMMEDIATELY -> lock.tryLock(0, leaseTimeSeconds, TimeUnit.SECONDS);
      case WAIT_AND_SKIP -> lock.tryLock(waitTimeSeconds, leaseTimeSeconds, TimeUnit.SECONDS);
    };
  }

  private String formatMethodSignature(ProceedingJoinPoint joinPoint) {
    final MethodSignature signature = (MethodSignature) joinPoint.getSignature();
    return signature.getDeclaringType().getSimpleName() + "." + signature.getName();
  }

  private Object handleSkip(
      SkipBehavior skipBehavior, ProceedingJoinPoint joinPoint, String lockKey, String methodName) {
    return switch (skipBehavior) {
      case THROW_EXCEPTION -> throw new LockNotAcquiredException(lockKey, methodName);
      case RETURN_DEFAULT -> getDefaultReturnValue(joinPoint);
    };
  }

  private Object getDefaultReturnValue(ProceedingJoinPoint joinPoint) {
    final Class<?> returnType = ((MethodSignature) joinPoint.getSignature()).getReturnType();
    if (returnType == void.class) return null;
    if (returnType == boolean.class) return false;
    if (returnType == int.class) return 0;
    if (returnType == long.class) return 0L;
    if (returnType == double.class) return 0.0d;
    if (returnType == float.class) return 0.0f;
    if (returnType == byte.class) return (byte) 0;
    if (returnType == short.class) return (short) 0;
    if (returnType == char.class) return '\u0000';
    return null;
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
   * @param keyExpression the key expression (literal or SpEL)
   * @param method the method being invoked
   * @param joinPoint the join point for accessing method arguments
   * @return the resolved key string
   */
  private String resolveKey(String keyExpression, Method method, ProceedingJoinPoint joinPoint) {
    if (!keyExpression.contains("#")) {
      return keyExpression;
    }

    EvaluationContext context =
        new MethodBasedEvaluationContext(
            null, method, joinPoint.getArgs(), PARAMETER_NAME_DISCOVERER);

    Object result = EXPRESSION_PARSER.parseExpression(keyExpression).getValue(context);

    if (result == null) {
      throw new IllegalArgumentException(
          "SpEL expression '"
              + keyExpression
              + "' evaluated to null for method: "
              + formatMethodSignature(joinPoint));
    }

    String resolvedKey = result.toString();
    if (resolvedKey == null || resolvedKey.isBlank()) {
      throw new IllegalArgumentException(
          "SpEL expression '"
              + keyExpression
              + "' evaluated to blank for method: "
              + formatMethodSignature(joinPoint));
    }

    return resolvedKey;
  }
}
