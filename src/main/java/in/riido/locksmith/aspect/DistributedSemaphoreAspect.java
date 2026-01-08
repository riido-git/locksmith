package in.riido.locksmith.aspect;

import in.riido.locksmith.DistributedSemaphore;
import in.riido.locksmith.LeaseExpirationBehavior;
import in.riido.locksmith.LockAcquisitionMode;
import in.riido.locksmith.autoconfigure.LocksmithProperties;
import in.riido.locksmith.autoconfigure.LocksmithProperties.SemaphoreProperties;
import in.riido.locksmith.exception.SemaphoreConfigurationException;
import in.riido.locksmith.exception.SemaphoreLeaseExpiredException;
import in.riido.locksmith.handler.SemaphoreContext;
import in.riido.locksmith.handler.SemaphoreSkipHandler;
import in.riido.locksmith.support.SpELKeyResolver;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RBucket;
import org.redisson.api.RPermitExpirableSemaphore;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.convert.DurationStyle;
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

  private static final Map<Class<? extends SemaphoreSkipHandler>, SemaphoreSkipHandler>
      HANDLER_CACHE = new ConcurrentHashMap<>(5);

  /** Cache to track permits per key within this JVM for consistency validation. */
  private final Map<String, Integer> keyToPermits = new ConcurrentHashMap<>();

  /** Cache to track which keys have been initialized in Redis by this JVM. */
  private final Map<String, Boolean> initializedKeys = new ConcurrentHashMap<>();

  private final RedissonClient redissonClient;
  private final SemaphoreProperties semaphoreProperties;

  /**
   * Constructs a new DistributedSemaphoreAspect.
   *
   * @param redissonClient the Redisson client for Redis operations
   * @param properties the configuration properties
   */
  public DistributedSemaphoreAspect(RedissonClient redissonClient, LocksmithProperties properties) {
    this.redissonClient = redissonClient;
    this.semaphoreProperties = properties.semaphore();
  }

  /**
   * Around advice that handles the distributed semaphore lifecycle for annotated methods.
   *
   * @param joinPoint the join point representing the intercepted method
   * @return the result of the method execution, or a default value if skipped
   * @throws Throwable if the method execution throws an exception
   */
  @Around("@annotation(in.riido.locksmith.DistributedSemaphore)")
  public Object handleDistributedSemaphore(ProceedingJoinPoint joinPoint) throws Throwable {
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
        resolveDuration(annotation.leaseTime(), semaphoreProperties.leaseTime());
    final Duration waitTime =
        resolveDuration(annotation.waitTime(), semaphoreProperties.waitTime());

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

    try {
      permitId = tryAcquirePermit(semaphore, annotation.mode(), waitTime, leaseTime);

      if (permitId == null) {
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
        return handleSkip(annotation, joinPoint, semaphoreKey, methodName);
      }

      LOG.info("Permit [{}] acquired from [{}] for [{}]", permitId, semaphoreKey, methodName);

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

      checkLeaseExpiration(
          annotation.onLeaseExpired(), leaseTime, executionTime, semaphoreKey, methodName);

      return result;

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOG.warn(
          "Thread interrupted while waiting for permit from [{}] in [{}]",
          semaphoreKey,
          methodName);
      return handleSkip(annotation, joinPoint, semaphoreKey, methodName);
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
  private void validatePermitsConsistency(String semaphoreKey, int permits, String methodName) {
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
   */
  private void ensureSemaphoreInitialized(String semaphoreKey, int permits) {
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
  private String tryAcquirePermit(
      RPermitExpirableSemaphore semaphore,
      LockAcquisitionMode mode,
      Duration waitTime,
      Duration leaseTime)
      throws InterruptedException {
    final long leaseTimeMs = leaseTime.toMillis();
    final long waitTimeMs = waitTime.toMillis();

    return switch (mode) {
      case SKIP_IMMEDIATELY -> semaphore.tryAcquire(0, leaseTimeMs, TimeUnit.MILLISECONDS);
      case WAIT_AND_SKIP -> semaphore.tryAcquire(waitTimeMs, leaseTimeMs, TimeUnit.MILLISECONDS);
    };
  }

  private void releasePermit(
      RPermitExpirableSemaphore semaphore,
      String permitId,
      String semaphoreKey,
      String methodName) {
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
          "Failed to release permit [{}] from [{}] for [{}]: {}",
          permitId,
          semaphoreKey,
          methodName,
          e.getMessage());
    }
  }

  private void checkLeaseExpiration(
      LeaseExpirationBehavior behavior,
      Duration leaseTime,
      long executionTimeMs,
      String semaphoreKey,
      String methodName) {

    final long leaseTimeMs = leaseTime.toMillis();

    if (executionTimeMs <= leaseTimeMs) {
      return;
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

  private String formatMethodSignature(ProceedingJoinPoint joinPoint) {
    final MethodSignature signature = (MethodSignature) joinPoint.getSignature();
    return signature.getDeclaringType().getSimpleName() + "." + signature.getName();
  }

  private SemaphoreSkipHandler getHandlerInstance(
      Class<? extends SemaphoreSkipHandler> handlerClass) {
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
      DistributedSemaphore annotation,
      ProceedingJoinPoint joinPoint,
      String semaphoreKey,
      String methodName) {
    final SemaphoreSkipHandler handler = getHandlerInstance(annotation.skipHandler());
    final MethodSignature signature = (MethodSignature) joinPoint.getSignature();
    final SemaphoreContext context =
        new SemaphoreContext(
            semaphoreKey,
            methodName,
            signature.getMethod(),
            joinPoint.getArgs(),
            signature.getReturnType());
    return handler.handle(context);
  }

  private Duration resolveDuration(String durationString, Duration defaultValue) {
    if (durationString == null || durationString.isBlank()) {
      return defaultValue;
    }
    return DurationStyle.detectAndParse(durationString);
  }
}
