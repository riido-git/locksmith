package in.riido.locksmith.aspect;

import in.riido.locksmith.AcquisitionMode;
import in.riido.locksmith.DistributedSemaphore;
import in.riido.locksmith.LeaseExpirationBehavior;
import in.riido.locksmith.autoconfigure.LocksmithProperties;
import in.riido.locksmith.autoconfigure.LocksmithProperties.SemaphoreProperties;
import in.riido.locksmith.exception.SemaphoreConfigurationException;
import in.riido.locksmith.exception.SemaphoreLeaseExpiredException;
import in.riido.locksmith.handler.SemaphoreSkipHandler;
import in.riido.locksmith.metrics.SemaphoreMetrics;
import in.riido.locksmith.models.SemaphoreContext;
import in.riido.locksmith.support.AspectSupport;
import in.riido.locksmith.support.DurationResolver;
import in.riido.locksmith.support.SemaphoreInitializer;
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
import org.redisson.api.RPermitExpirableSemaphore;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * Aspect that handles distributed semaphore-based concurrency control for methods annotated with
 * {@link DistributedSemaphore}. Uses Redisson's RPermitExpirableSemaphore for distributed permit
 * management with automatic lease expiration.
 *
 * <p>This aspect is ordered with {@link Ordered#HIGHEST_PRECEDENCE} to ensure permits are acquired
 * before any transaction starts.
 *
 * @author Garvit Joshi
 * @since 2.0.0
 */
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DistributedSemaphoreAspect {

  private static final Logger LOG = LoggerFactory.getLogger(DistributedSemaphoreAspect.class);

  private final SemaphoreInitializer semaphoreInitializer;

  /** Cache of handler instances per class type for reuse. */
  private final Map<Class<? extends SemaphoreSkipHandler>, SemaphoreSkipHandler> handlerCache =
      new ConcurrentHashMap<>(5);

  private final RedissonClient redissonClient;
  private final SemaphoreProperties semaphoreProperties;
  private final ApplicationContext applicationContext;
  @Nullable private final SemaphoreMetrics semaphoreMetrics;

  /**
   * Constructs a new DistributedSemaphoreAspect.
   *
   * @param redissonClient the Redisson client for Redis operations
   * @param properties the configuration properties
   * @param applicationContext the Spring application context for handler bean lookup
   */
  public DistributedSemaphoreAspect(
      @NonNull RedissonClient redissonClient,
      @NonNull LocksmithProperties properties,
      @NonNull ApplicationContext applicationContext) {
    this(redissonClient, properties, applicationContext, null);
  }

  /**
   * Constructs a new DistributedSemaphoreAspect with optional metrics support.
   *
   * @param redissonClient the Redisson client for Redis operations
   * @param properties the configuration properties
   * @param applicationContext the Spring application context for handler bean lookup
   * @param semaphoreMetrics the optional semaphore metrics for observability
   */
  public DistributedSemaphoreAspect(
      @NonNull RedissonClient redissonClient,
      @NonNull LocksmithProperties properties,
      @NonNull ApplicationContext applicationContext,
      @Nullable SemaphoreMetrics semaphoreMetrics) {
    this.redissonClient = redissonClient;
    this.semaphoreProperties = properties.semaphore();
    this.applicationContext = applicationContext;
    this.semaphoreMetrics = semaphoreMetrics;
    this.semaphoreInitializer = new SemaphoreInitializer(redissonClient);
  }

  /**
   * Around advice that handles the distributed semaphore lifecycle for annotated methods.
   *
   * @param joinPoint the join point representing the intercepted method
   * @return the result of the method execution, or a default value if skipped
   * @throws Throwable if the method execution throws an exception
   */
  @Around("@annotation(in.riido.locksmith.DistributedSemaphore)")
  @Nullable
  public Object handleDistributedSemaphore(@NonNull ProceedingJoinPoint joinPoint)
      throws Throwable {
    final MethodSignature signature = (MethodSignature) joinPoint.getSignature();
    final DistributedSemaphore annotation =
        signature.getMethod().getAnnotation(DistributedSemaphore.class);
    final boolean debugMode = semaphoreProperties.debug();
    final String methodName = AspectSupport.formatMethodSignature(joinPoint);

    // Validate key is not blank
    if (annotation.key().isBlank()) {
      throw new IllegalArgumentException(
          "DistributedSemaphore key must not be blank on method: "
              + signature.getDeclaringType().getName()
              + "."
              + signature.getName());
    }

    // Validate permits is positive
    if (annotation.permits() <= 0) {
      throw new SemaphoreConfigurationException(
          String.format(
              "DistributedSemaphore permits must be positive on method [%s], got: %d",
              methodName, annotation.permits()),
          annotation.key());
    }

    final String resolvedKey = SpELKeyResolver.resolve(annotation.key(), joinPoint);
    final String semaphoreKey = semaphoreProperties.keyPrefix() + resolvedKey;
    final int permits = annotation.permits();

    // Validate consistency: same key must have same permits within this codebase
    semaphoreInitializer.validatePermitsConsistency(semaphoreKey, permits, methodName);

    // Initialize semaphore in Redis (first time only per key per JVM)
    semaphoreInitializer.ensureInitialized(semaphoreKey, permits);

    final RPermitExpirableSemaphore semaphore =
        redissonClient.getPermitExpirableSemaphore(semaphoreKey);

    final Duration leaseTime =
        DurationResolver.resolve(annotation.leaseTime(), semaphoreProperties.leaseTime());
    final Duration waitTime =
        DurationResolver.resolve(annotation.waitTime(), semaphoreProperties.waitTime());

    if (debugMode) {
      LOG.info(
          "Acquiring permit from [{}] for [{}] - permits={}, mode={}, leaseTime={}, waitTime={}",
          semaphoreKey,
          methodName,
          permits,
          annotation.mode(),
          leaseTime,
          waitTime);
    }

    String permitId = null;
    long startTime = 0;
    final long acquisitionStartTime = System.currentTimeMillis();

    try {
      permitId = tryAcquirePermit(semaphore, annotation.mode(), waitTime, leaseTime);

      if (permitId == null) {
        if (semaphoreMetrics != null) {
          semaphoreMetrics.recordSkipped(annotation.mode());
        }
        if (debugMode) {
          LOG.info(
              "Permit acquisition failed for [{}] in [{}], invoking skip handler: {}",
              semaphoreKey,
              methodName,
              annotation.skipHandler().getSimpleName());
        } else {
          LOG.info(
              "Skipping execution of [{}] - no permit available from semaphore [{}]",
              methodName,
              semaphoreKey);
        }
        return handleSkip(annotation, joinPoint, semaphoreKey, methodName, permitId);
      }

      if (semaphoreMetrics != null) {
        semaphoreMetrics.recordAcquisitionTime(System.currentTimeMillis() - acquisitionStartTime);
        semaphoreMetrics.recordAcquired();
      }

      LOG.info("Permit [{}] acquired from [{}] for [{}]", permitId, semaphoreKey, methodName);

      startTime = System.currentTimeMillis();
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

      checkLeaseExpiration(
          annotation.onLeaseExpired(), leaseTime, executionTime, semaphoreKey, methodName);

      return result;

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      if (permitId != null) {
        // InterruptedException came from joinPoint.proceed() (user's method), not from
        // permit acquisition. Propagate the original exception instead of swallowing it.
        throw e;
      }
      // Permit acquisition was interrupted
      LOG.warn(
          "Thread interrupted while waiting for permit from [{}] in [{}]",
          semaphoreKey,
          methodName);
      if (semaphoreMetrics != null) {
        semaphoreMetrics.recordSkipped(annotation.mode());
      }
      return handleSkip(annotation, joinPoint, semaphoreKey, methodName, permitId);
    } finally {
      if (permitId != null) {
        if (semaphoreMetrics != null) {
          semaphoreMetrics.recordHeldTime(System.currentTimeMillis() - startTime);
        }
        releasePermit(semaphore, permitId, semaphoreKey, methodName);
      }
    }
  }

  /**
   * Attempts to acquire a permit from the semaphore.
   *
   * @return the permit ID if acquired, null otherwise
   */
  @Nullable
  private String tryAcquirePermit(
      @NonNull RPermitExpirableSemaphore semaphore,
      @NonNull AcquisitionMode mode,
      @NonNull Duration waitTime,
      @NonNull Duration leaseTime)
      throws InterruptedException {
    final long leaseTimeMs = leaseTime.toMillis();
    final long waitTimeMs = waitTime.toMillis();

    return switch (mode) {
      case SKIP_IMMEDIATELY -> semaphore.tryAcquire(0, leaseTimeMs, TimeUnit.MILLISECONDS);
      case WAIT_AND_SKIP -> semaphore.tryAcquire(waitTimeMs, leaseTimeMs, TimeUnit.MILLISECONDS);
    };
  }

  private void releasePermit(
      @NonNull RPermitExpirableSemaphore semaphore,
      @NonNull String permitId,
      @NonNull String semaphoreKey,
      @NonNull String methodName) {
    try {
      semaphore.release(permitId);
      LOG.info("Permit [{}] released from [{}] for [{}]", permitId, semaphoreKey, methodName);
    } catch (IllegalArgumentException e) {
      // Permit may have expired
      LOG.warn(
          "Permit [{}] was already released (possibly expired) from [{}] for [{}]: {}",
          permitId,
          semaphoreKey,
          methodName,
          e.getMessage());
    } catch (Exception e) {
      // Handle other Redis exceptions (e.g., when permit has already expired)
      LOG.warn(
          "Failed to release permit [{}] from [{}] for [{}]",
          permitId,
          semaphoreKey,
          methodName,
          e);
    }
  }

  private void checkLeaseExpiration(
      @NonNull LeaseExpirationBehavior behavior,
      @NonNull Duration leaseTime,
      long executionTimeMs,
      @NonNull String semaphoreKey,
      @NonNull String methodName) {

    final long leaseTimeMs = leaseTime.toMillis();

    if (executionTimeMs <= leaseTimeMs) {
      return;
    }

    if (semaphoreMetrics != null) {
      semaphoreMetrics.recordLeaseExpired();
    }

    switch (behavior) {
      case LOG_WARNING ->
          LOG.warn(
              "Semaphore [{}] permit lease may have expired during execution of [{}]. "
                  + "Lease time: {}ms, Execution time: {}ms. "
                  + "Consider increasing the lease time.",
              semaphoreKey,
              methodName,
              leaseTimeMs,
              executionTimeMs);
      case THROW_EXCEPTION ->
          throw new SemaphoreLeaseExpiredException(
              semaphoreKey, methodName, leaseTimeMs, executionTimeMs);
      case IGNORE -> {
        // Do nothing
      }
    }
  }

  @NonNull
  private SemaphoreSkipHandler getHandlerInstance(
      @NonNull Class<? extends SemaphoreSkipHandler> handlerClass) {
    return handlerCache.computeIfAbsent(
        handlerClass,
        clazz ->
            AspectSupport.resolveHandler(clazz, applicationContext, semaphoreProperties.debug()));
  }

  @Nullable
  private Object handleSkip(
      @NonNull DistributedSemaphore annotation,
      @NonNull ProceedingJoinPoint joinPoint,
      @NonNull String semaphoreKey,
      @NonNull String methodName,
      @Nullable String permitId) {
    final SemaphoreSkipHandler handler = getHandlerInstance(annotation.skipHandler());
    final MethodSignature signature = (MethodSignature) joinPoint.getSignature();
    final SemaphoreContext context =
        new SemaphoreContext(
            semaphoreKey,
            methodName,
            signature.getMethod(),
            joinPoint.getArgs(),
            signature.getReturnType(),
            permitId);
    return handler.handle(context);
  }
}
