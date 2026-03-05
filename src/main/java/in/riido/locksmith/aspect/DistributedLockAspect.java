package in.riido.locksmith.aspect;

import in.riido.locksmith.AcquisitionMode;
import in.riido.locksmith.DistributedLock;
import in.riido.locksmith.LeaseExpirationBehavior;
import in.riido.locksmith.LockType;
import in.riido.locksmith.autoconfigure.LocksmithProperties;
import in.riido.locksmith.autoconfigure.LocksmithProperties.LockProperties;
import in.riido.locksmith.exception.LeaseExpiredException;
import in.riido.locksmith.handler.LockSkipHandler;
import in.riido.locksmith.metrics.LockMetrics;
import in.riido.locksmith.models.LockContext;
import in.riido.locksmith.support.DurationResolver;
import in.riido.locksmith.support.SpELKeyResolver;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

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

  private final RedissonClient redissonClient;
  private final LockProperties lockProperties;
  private final ApplicationContext applicationContext;
  @Nullable private final LockMetrics lockMetrics;

  private final Map<Class<? extends LockSkipHandler>, LockSkipHandler> handlerCache =
      new ConcurrentHashMap<>(5);

  /**
   * Constructs a new DistributedLockAspect.
   *
   * @param redissonClient the Redisson client for Redis operations
   * @param properties the configuration properties
   * @param applicationContext the Spring application context for handler bean lookup
   */
  public DistributedLockAspect(
      @NonNull RedissonClient redissonClient,
      @NonNull LocksmithProperties properties,
      @NonNull ApplicationContext applicationContext) {
    this(redissonClient, properties, applicationContext, null);
  }

  /**
   * Constructs a new DistributedLockAspect with optional metrics support.
   *
   * @param redissonClient the Redisson client for Redis operations
   * @param properties the configuration properties
   * @param applicationContext the Spring application context for handler bean lookup
   * @param lockMetrics the optional lock metrics for observability
   */
  public DistributedLockAspect(
      @NonNull RedissonClient redissonClient,
      @NonNull LocksmithProperties properties,
      @NonNull ApplicationContext applicationContext,
      @Nullable LockMetrics lockMetrics) {
    this.redissonClient = redissonClient;
    this.lockProperties = properties.lock();
    this.applicationContext = applicationContext;
    this.lockMetrics = lockMetrics;
  }

  /**
   * Around advice that handles the distributed lock lifecycle for annotated methods.
   *
   * @param joinPoint the join point representing the intercepted method
   * @return the result of the method execution, or a default value if skipped
   * @throws Throwable if the method execution throws an exception
   */
  @Around("@annotation(in.riido.locksmith.DistributedLock)")
  @Nullable
  public Object handleDistributedLock(@NonNull ProceedingJoinPoint joinPoint) throws Throwable {
    final MethodSignature signature = (MethodSignature) joinPoint.getSignature();
    final DistributedLock annotation = signature.getMethod().getAnnotation(DistributedLock.class);
    final boolean debugMode = Boolean.TRUE.equals(lockProperties.debug());
    final String methodName = formatMethodSignature(joinPoint);

    if (annotation.key().isBlank()) {
      throw new IllegalArgumentException(
          "DistributedLock key must not be blank on method: "
              + signature.getDeclaringType().getName()
              + "."
              + signature.getName());
    }

    final String resolvedKey = SpELKeyResolver.resolve(annotation.key(), joinPoint);
    final String lockKey = lockProperties.keyPrefix() + resolvedKey;
    final RLock lock = getLock(lockKey, annotation.type());
    final boolean autoRenew = annotation.autoRenew();

    // Log warnings for conflicting settings when autoRenew is enabled
    if (autoRenew) {
      if (!annotation.leaseTime().isBlank()) {
        LOG.warn(
            "autoRenew is enabled for [{}] but leaseTime is also specified. "
                + "leaseTime will be ignored as Redisson's watchdog will manage lease renewal.",
            methodName);
      }
      if (annotation.onLeaseExpired() == LeaseExpirationBehavior.THROW_EXCEPTION) {
        LOG.warn(
            "autoRenew is enabled for [{}] but onLeaseExpired is set to THROW_EXCEPTION. "
                + "This setting will have no effect as the lock will never expire during execution.",
            methodName);
      }
    }

    final Duration leaseTime =
        autoRenew
            ? Duration.ofMillis(-1)
            : DurationResolver.resolve(annotation.leaseTime(), lockProperties.leaseTime());
    final Duration waitTime =
        DurationResolver.resolve(annotation.waitTime(), lockProperties.waitTime());

    if (debugMode) {
      LOG.info(
          "Acquiring lock [{}] for [{}] - type={}, mode={}, leaseTime={}, waitTime={}, autoRenew={}",
          lockKey,
          methodName,
          annotation.type(),
          annotation.mode(),
          leaseTime,
          waitTime,
          autoRenew);
    }

    boolean lockAcquired = false;
    final long acquisitionStartTime = System.currentTimeMillis();

    try {
      lockAcquired = tryAcquireLock(lock, annotation.mode(), waitTime, leaseTime);

      if (!lockAcquired) {
        if (lockMetrics != null) {
          String reason =
              annotation.mode() == AcquisitionMode.SKIP_IMMEDIATELY ? "immediate" : "timeout";
          lockMetrics.recordSkipped(reason);
        }
        if (debugMode) {
          LOG.info(
              "Lock acquisition failed for [{}] in [{}], invoking skip handler: {}",
              lockKey,
              methodName,
              annotation.skipHandler().getSimpleName());
        } else {
          LOG.info(
              "Skipping execution of [{}] - lock [{}] is held by another instance",
              methodName,
              lockKey);
        }
        return handleSkip(annotation, joinPoint, lockKey, methodName);
      }

      if (lockMetrics != null) {
        lockMetrics.recordAcquisitionTime(System.currentTimeMillis() - acquisitionStartTime);
        lockMetrics.recordAcquired();
        if (autoRenew) {
          lockMetrics.incrementAutoRenewActive();
        }
      }

      LOG.info("Lock [{}] acquired for [{}]", lockKey, methodName);

      final long startTime = System.currentTimeMillis();
      final Object result = joinPoint.proceed();
      final long executionTime = System.currentTimeMillis() - startTime;

      if (lockMetrics != null) {
        lockMetrics.recordHeldTime(executionTime);
      }

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
            annotation.onLeaseExpired(), leaseTime, executionTime, lockKey, methodName);
      }

      return result;

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      if (lockAcquired) {
        // InterruptedException came from joinPoint.proceed() (user's method), not from
        // lock acquisition. Propagate the original exception instead of swallowing it.
        throw e;
      }
      // Lock acquisition was interrupted
      LOG.warn("Thread interrupted while waiting for lock [{}] in [{}]", lockKey, methodName);
      if (lockMetrics != null) {
        String reason =
            annotation.mode() == AcquisitionMode.SKIP_IMMEDIATELY ? "immediate" : "timeout";
        lockMetrics.recordSkipped(reason);
      }
      return handleSkip(annotation, joinPoint, lockKey, methodName);
    } finally {
      if (autoRenew && lockMetrics != null && lockAcquired) {
        lockMetrics.decrementAutoRenewActive();
      }
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
      @NonNull LeaseExpirationBehavior behavior,
      @NonNull Duration leaseTime,
      long executionTimeMs,
      @NonNull String lockKey,
      @NonNull String methodName) {

    final long leaseTimeMs = leaseTime.toMillis();

    if (executionTimeMs <= leaseTimeMs) {
      return;
    }

    if (lockMetrics != null) {
      lockMetrics.recordLeaseExpired();
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

  private void releaseLock(
      @NonNull RLock lock, @NonNull String lockKey, @NonNull String methodName) {
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
    } catch (Exception e) {
      // Handle other exceptions (e.g., Redis connection failures)
      LOG.warn("Failed to release lock [{}] for [{}]", lockKey, methodName, e);
    }
  }

  /**
   * Gets the appropriate lock based on the lock type.
   *
   * @param lockKey the key for the lock
   * @param lockType the type of lock to acquire
   * @return the appropriate RLock instance
   */
  @NonNull
  private RLock getLock(@NonNull String lockKey, @NonNull LockType lockType) {
    return switch (lockType) {
      case REENTRANT -> redissonClient.getLock(lockKey);
      case READ -> redissonClient.getReadWriteLock(lockKey).readLock();
      case WRITE -> redissonClient.getReadWriteLock(lockKey).writeLock();
    };
  }

  private boolean tryAcquireLock(
      @NonNull RLock lock,
      @NonNull AcquisitionMode mode,
      @NonNull Duration waitTime,
      @NonNull Duration leaseTime)
      throws InterruptedException {
    final long leaseTimeMs = leaseTime.toMillis();
    final long waitTimeMs = waitTime.toMillis();
    return switch (mode) {
      case SKIP_IMMEDIATELY -> lock.tryLock(0, leaseTimeMs, TimeUnit.MILLISECONDS);
      case WAIT_AND_SKIP -> lock.tryLock(waitTimeMs, leaseTimeMs, TimeUnit.MILLISECONDS);
    };
  }

  @NonNull
  private String formatMethodSignature(@NonNull ProceedingJoinPoint joinPoint) {
    final MethodSignature signature = (MethodSignature) joinPoint.getSignature();
    return signature.getDeclaringType().getSimpleName() + "." + signature.getName();
  }

  /**
   * Gets a cached instance of the specified handler class, creating it if necessary.
   *
   * <p>This method provides thread-safe caching of handler instances to avoid the overhead of
   * instantiation on every lock skip. Handler instances are cached per class type and reused across
   * all invocations.
   *
   * <p>Handler resolution follows this order:
   *
   * <ol>
   *   <li>Look up the handler as a Spring bean from ApplicationContext by type
   *   <li>Fall back to reflection-based instantiation (requires public no-arg constructor)
   * </ol>
   *
   * <p>This allows handlers to be defined as Spring beans with dependency injection:
   *
   * <pre>{@code
   * @Component
   * public class AlertingSkipHandler implements LockSkipHandler {
   *     private final AlertService alertService;
   *
   *     public AlertingSkipHandler(AlertService alertService) {
   *         this.alertService = alertService;
   *     }
   *
   *     @Override
   *     public Object handle(LockContext context) {
   *         alertService.sendAlert("Lock failed: " + context.lockKey());
   *         return null;
   *     }
   * }
   * }</pre>
   *
   * <p><b>Important:</b> Handler classes must be stateless and thread-safe, as a single instance
   * will be shared across all concurrent invocations.
   *
   * @param handlerClass the handler class to instantiate
   * @return a cached or newly created instance of the handler
   * @throws IllegalStateException if the handler cannot be instantiated
   */
  @NonNull
  private LockSkipHandler getHandlerInstance(
      @NonNull Class<? extends LockSkipHandler> handlerClass) {
    return handlerCache.computeIfAbsent(
        handlerClass,
        clazz -> {
          // First, try to get the handler as a Spring bean (only if context is active)
          if (isApplicationContextActive()) {
            try {
              LockSkipHandler bean = applicationContext.getBean(clazz);
              if (bean != null) {
                return bean;
              }
            } catch (BeansException ignored) {
              // Bean not found, will fall back to reflection
            }
          } else {
            if (Boolean.TRUE.equals(lockProperties.debug())) {
              LOG.info(
                  "ApplicationContext is not active, skipping Spring bean lookup for handler: {}",
                  clazz.getName());
            }
          }
          // Not a Spring bean, fall back to reflection
          if (Boolean.TRUE.equals(lockProperties.debug())) {
            LOG.info(
                "Handler {} not found as Spring bean, falling back to reflection-based instantiation",
                clazz.getName());
          }

          // Fall back to reflection-based instantiation
          try {
            return clazz.getDeclaredConstructor().newInstance();
          } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                "Failed to instantiate skip handler: "
                    + clazz.getName()
                    + ". Ensure it is a Spring bean or has a public no-argument constructor.",
                e);
          }
        });
  }

  /**
   * Checks if the application context is active and can be used for bean lookups.
   *
   * @return true if the context is active, false otherwise
   */
  private boolean isApplicationContextActive() {
    if (applicationContext instanceof ConfigurableApplicationContext configurableContext) {
      return configurableContext.isActive();
    }
    // For non-configurable contexts, assume active
    return true;
  }

  @Nullable
  private Object handleSkip(
      @NonNull DistributedLock annotation,
      @NonNull ProceedingJoinPoint joinPoint,
      @NonNull String lockKey,
      @NonNull String methodName) {
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
}
