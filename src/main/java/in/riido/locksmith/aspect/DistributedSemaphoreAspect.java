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
import org.redisson.api.RBucket;
import org.redisson.api.RPermitExpirableSemaphore;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
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
  private static final String META_SUFFIX = ":meta";

  /** Cache to track permits per key within this JVM for consistency validation. */
  private final Map<String, Integer> keyToPermits = new ConcurrentHashMap<>();

  /** Cache to track which keys have been initialized in Redis by this JVM. */
  private final Map<String, Boolean> initializedKeys = new ConcurrentHashMap<>();

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
    final boolean debugMode = Boolean.TRUE.equals(semaphoreProperties.debug());
    final String methodName = formatMethodSignature(joinPoint);

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
    validatePermitsConsistency(semaphoreKey, permits, methodName);

    // Initialize semaphore in Redis (first time only per key per JVM)
    ensureSemaphoreInitialized(semaphoreKey, permits);

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
    final long acquisitionStartTime = System.currentTimeMillis();

    try {
      permitId = tryAcquirePermit(semaphore, annotation.mode(), waitTime, leaseTime);

      if (permitId == null) {
        if (semaphoreMetrics != null) {
          String reason =
              annotation.mode() == AcquisitionMode.SKIP_IMMEDIATELY ? "immediate" : "timeout";
          semaphoreMetrics.recordSkipped(reason);
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

      final long startTime = System.currentTimeMillis();
      final Object result = joinPoint.proceed();
      final long executionTime = System.currentTimeMillis() - startTime;

      if (semaphoreMetrics != null) {
        semaphoreMetrics.recordHeldTime(executionTime);
      }

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
        String reason =
            annotation.mode() == AcquisitionMode.SKIP_IMMEDIATELY ? "immediate" : "timeout";
        semaphoreMetrics.recordSkipped(reason);
      }
      return handleSkip(annotation, joinPoint, semaphoreKey, methodName, permitId);
    } finally {
      if (permitId != null) {
        releasePermit(semaphore, permitId, semaphoreKey, methodName);
      }
    }
  }

  /**
   * Validates that the same semaphore key is not used with different permits values within this
   * codebase.
   */
  private void validatePermitsConsistency(
      @NonNull String semaphoreKey, int permits, @NonNull String methodName) {
    Integer existingPermits = keyToPermits.putIfAbsent(semaphoreKey, permits);
    if (existingPermits != null && existingPermits != permits) {
      throw new SemaphoreConfigurationException(
          String.format(
              "Semaphore key '%s' is used with inconsistent permits: %d (existing) vs %d (in %s). "
                  + "Each key must have the same permits across all usages.",
              semaphoreKey, existingPermits, permits, methodName),
          semaphoreKey);
    }
  }

  /**
   * Ensures the semaphore is initialized in Redis with the configured permits. Uses metadata
   * storage to detect and warn about permit mismatches across deployments.
   *
   * <p><b>Race Condition Note:</b> There is a small race window between {@code trySetPermits} and
   * {@code metaBucket.set} where another instance could read null from the metadata bucket. This is
   * acceptable because:
   *
   * <ul>
   *   <li>Redisson's {@code trySetPermits} is itself atomic - only one instance will successfully
   *       create the semaphore
   *   <li>The metadata is only used for logging warnings about permit mismatches
   *   <li>Worst case: duplicate "created semaphore" log messages on first initialization
   *   <li>The semaphore's actual permit count in Redis is always correct
   * </ul>
   *
   * <p>A fully atomic solution would require a Lua script, but the added complexity is not
   * justified for this edge case that only affects logging during first initialization.
   */
  private void ensureSemaphoreInitialized(@NonNull String semaphoreKey, int permits) {
    if (initializedKeys.containsKey(semaphoreKey)) {
      return; // Already initialized by this JVM
    }

    String metaKey = semaphoreKey + META_SUFFIX;
    RBucket<Integer> metaBucket = redissonClient.getBucket(metaKey);
    RPermitExpirableSemaphore semaphore = redissonClient.getPermitExpirableSemaphore(semaphoreKey);

    Integer existingPermits = metaBucket.get();

    if (existingPermits == null) {
      // First time - create semaphore and metadata
      boolean created = semaphore.trySetPermits(permits);
      if (created) {
        metaBucket.set(permits);
        LOG.info("Created semaphore [{}] with {} permits", semaphoreKey, permits);
      } else {
        // Race condition: another instance created it between our check and set
        // Read the actual value and warn if different
        existingPermits = metaBucket.get();
        if (existingPermits != null && existingPermits != permits) {
          LOG.warn(
              "Semaphore [{}] was created by another instance with {} permits, "
                  + "but this instance configured {} permits. Using existing value. "
                  + "To change: delete Redis keys '{}' and '{}', then redeploy all instances.",
              semaphoreKey,
              existingPermits,
              permits,
              semaphoreKey,
              metaKey);
        }
      }
    } else if (existingPermits != permits) {
      // Semaphore exists with different permits
      LOG.warn(
          "Semaphore [{}] exists with {} permits, but this instance configured {} permits. "
              + "Using existing value. To change: delete Redis keys '{}' and '{}', "
              + "then redeploy all instances.",
          semaphoreKey,
          existingPermits,
          permits,
          semaphoreKey,
          metaKey);
    }

    initializedKeys.put(semaphoreKey, Boolean.TRUE);
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
  private String formatMethodSignature(@NonNull ProceedingJoinPoint joinPoint) {
    final MethodSignature signature = (MethodSignature) joinPoint.getSignature();
    return signature.getDeclaringType().getSimpleName() + "." + signature.getName();
  }

  /**
   * Gets a cached instance of the specified handler class, creating it if necessary.
   *
   * <p>This method provides thread-safe caching of handler instances to avoid the overhead of
   * instantiation on every permit skip. Handler instances are cached per class type and reused
   * across all invocations.
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
   * public class AlertingSemaphoreHandler implements SemaphoreSkipHandler {
   *     private final AlertService alertService;
   *
   *     public AlertingSemaphoreHandler(AlertService alertService) {
   *         this.alertService = alertService;
   *     }
   *
   *     @Override
   *     public Object handle(SemaphoreContext context) {
   *         alertService.sendAlert("Permit failed: " + context.semaphoreKey());
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
  private SemaphoreSkipHandler getHandlerInstance(
      @NonNull Class<? extends SemaphoreSkipHandler> handlerClass) {
    return handlerCache.computeIfAbsent(
        handlerClass,
        clazz -> {
          // First, try to get the handler as a Spring bean (only if context is active)
          if (isApplicationContextActive()) {
            try {
              SemaphoreSkipHandler bean = applicationContext.getBean(clazz);
              if (bean != null) {
                return bean;
              }
            } catch (BeansException ignored) {
              // Bean not found, will fall back to reflection
            }
          } else {
            if (Boolean.TRUE.equals(semaphoreProperties.debug())) {
              LOG.info(
                  "ApplicationContext is not active, skipping Spring bean lookup for handler: {}",
                  clazz.getName());
            }
          }
          // Not a Spring bean, fall back to reflection
          if (Boolean.TRUE.equals(semaphoreProperties.debug())) {
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
