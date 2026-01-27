package in.riido.locksmith.aspect;

import in.riido.locksmith.AcquisitionMode;
import in.riido.locksmith.RateLimit;
import in.riido.locksmith.RateType;
import in.riido.locksmith.autoconfigure.LocksmithProperties;
import in.riido.locksmith.autoconfigure.LocksmithProperties.RateLimitProperties;
import in.riido.locksmith.handler.RateLimitSkipHandler;
import in.riido.locksmith.metrics.RateLimitMetrics;
import in.riido.locksmith.models.RateLimitContext;
import in.riido.locksmith.support.DurationResolver;
import in.riido.locksmith.support.SpELKeyResolver;
import java.io.Serial;
import java.io.Serializable;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.redisson.api.RBucket;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * Aspect that handles distributed rate limiting for methods annotated with {@link RateLimit}. Uses
 * Redisson's RRateLimiter for distributed rate limiting across all server instances.
 *
 * <p>This aspect is ordered with {@link Ordered#HIGHEST_PRECEDENCE} to ensure rate limits are
 * checked before any transaction starts.
 *
 * @author Garvit Joshi
 * @since 3.0.0
 */
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RateLimitAspect {

  private static final Logger LOG = LoggerFactory.getLogger(RateLimitAspect.class);
  private static final String META_SUFFIX = ":rate-meta";

  /** Cache to track rate limiter configurations per key within this JVM. */
  private final Map<String, RateLimitConfig> initializedRateLimiters = new ConcurrentHashMap<>();

  /** Cache of handler instances per class type for reuse. */
  private final Map<Class<? extends RateLimitSkipHandler>, RateLimitSkipHandler> handlerCache =
      new ConcurrentHashMap<>(5);

  private final RedissonClient redissonClient;
  private final RateLimitProperties rateLimitProperties;
  private final ApplicationContext applicationContext;
  @Nullable private final RateLimitMetrics rateLimitMetrics;

  /**
   * Constructs a new RateLimitAspect.
   *
   * @param redissonClient the Redisson client for Redis operations
   * @param properties the configuration properties
   * @param applicationContext the Spring application context for handler bean lookup
   */
  public RateLimitAspect(
      @NonNull RedissonClient redissonClient,
      @NonNull LocksmithProperties properties,
      @NonNull ApplicationContext applicationContext) {
    this(redissonClient, properties, applicationContext, null);
  }

  /**
   * Constructs a new RateLimitAspect with optional metrics support.
   *
   * @param redissonClient the Redisson client for Redis operations
   * @param properties the configuration properties
   * @param applicationContext the Spring application context for handler bean lookup
   * @param rateLimitMetrics the optional rate limit metrics for observability
   */
  public RateLimitAspect(
      @NonNull RedissonClient redissonClient,
      @NonNull LocksmithProperties properties,
      @NonNull ApplicationContext applicationContext,
      @Nullable RateLimitMetrics rateLimitMetrics) {
    this.redissonClient = redissonClient;
    this.rateLimitProperties = properties.rateLimit();
    this.applicationContext = applicationContext;
    this.rateLimitMetrics = rateLimitMetrics;
  }

  /**
   * Around advice that handles the distributed rate limiting for annotated methods.
   *
   * @param joinPoint the join point representing the intercepted method
   * @return the result of the method execution, or a default value if rate limited
   * @throws Throwable if the method execution throws an exception
   */
  @Around("@annotation(in.riido.locksmith.RateLimit)")
  @Nullable
  public Object handleRateLimit(@NonNull ProceedingJoinPoint joinPoint) throws Throwable {
    final MethodSignature signature = (MethodSignature) joinPoint.getSignature();
    final RateLimit annotation = signature.getMethod().getAnnotation(RateLimit.class);
    final boolean debugMode = Boolean.TRUE.equals(rateLimitProperties.debug());
    final String methodName = formatMethodSignature(joinPoint);

    // Validate key is not blank
    if (annotation.key().isBlank()) {
      throw new IllegalArgumentException(
          "RateLimit key must not be blank on method: "
              + signature.getDeclaringType().getName()
              + "."
              + signature.getName());
    }

    // Validate permits is positive
    if (annotation.permits() <= 0) {
      throw new IllegalArgumentException(
          String.format(
              "RateLimit permits must be positive on method [%s], got: %d",
              methodName, annotation.permits()));
    }

    final String resolvedKey = SpELKeyResolver.resolve(annotation.key(), joinPoint);
    final String rateLimitKey = rateLimitProperties.keyPrefix() + resolvedKey;
    final long permits = annotation.permits();

    // Parse interval
    final Duration interval =
        DurationResolver.resolve(
            annotation.interval().isBlank() ? null : annotation.interval(), Duration.ofSeconds(1));

    // Validate interval is positive
    if (interval.isZero() || interval.isNegative()) {
      throw new IllegalArgumentException(
          String.format(
              "RateLimit interval must be positive on method [%s], got: %s", methodName, interval));
    }

    final Duration waitTime =
        DurationResolver.resolve(annotation.waitTime(), rateLimitProperties.waitTime());

    // Initialize rate limiter in Redis (first time only per key per JVM)
    ensureRateLimiterInitialized(rateLimitKey, permits, interval, annotation.type());

    final RRateLimiter rateLimiter = redissonClient.getRateLimiter(rateLimitKey);

    if (debugMode) {
      LOG.info(
          "Checking rate limit [{}] for [{}] - permits={}, interval={}, mode={}, waitTime={}",
          rateLimitKey,
          methodName,
          permits,
          interval,
          annotation.mode(),
          waitTime);
    }

    final long acquisitionStartTime = System.currentTimeMillis();
    boolean permitAcquired = tryAcquirePermit(rateLimiter, annotation.mode(), waitTime);

    if (!permitAcquired) {
      if (rateLimitMetrics != null) {
        String reason =
            annotation.mode() == AcquisitionMode.SKIP_IMMEDIATELY ? "immediate" : "timeout";
        rateLimitMetrics.recordExceeded(reason);
      }
      if (debugMode) {
        LOG.info(
            "Rate limit exceeded for [{}] in [{}], invoking skip handler: {}",
            rateLimitKey,
            methodName,
            annotation.skipHandler().getSimpleName());
      } else {
        LOG.info("Rate limit exceeded for [{}] - method [{}] skipped", rateLimitKey, methodName);
      }
      return handleSkip(annotation, joinPoint, rateLimitKey, methodName);
    }

    if (rateLimitMetrics != null) {
      rateLimitMetrics.recordAcquisitionTime(System.currentTimeMillis() - acquisitionStartTime);
      rateLimitMetrics.recordAcquired();
    }

    if (debugMode) {
      LOG.info("Rate limit permit acquired for [{}] in [{}]", rateLimitKey, methodName);
    }

    final long startTime = System.currentTimeMillis();
    final Object result = joinPoint.proceed();
    final long executionTime = System.currentTimeMillis() - startTime;

    if (rateLimitMetrics != null) {
      rateLimitMetrics.recordExecutionTime(executionTime);
    }

    if (debugMode) {
      LOG.info(
          "Method [{}] executed in {}ms, returnType={}, hasResult={}",
          methodName,
          executionTime,
          signature.getReturnType().getSimpleName(),
          result != null);
    }

    return result;
  }

  /**
   * Ensures the rate limiter is initialized in Redis with the configured rate. Uses metadata
   * storage to detect and warn about configuration mismatches across deployments.
   *
   * <p><b>Race Condition Note:</b> There is a small race window between {@code trySetRate} and
   * {@code metaBucket.set} where another instance could read null from the metadata bucket. This is
   * acceptable because:
   *
   * <ul>
   *   <li>Redisson's {@code trySetRate} is itself atomic - only one instance will successfully
   *       create the rate limiter
   *   <li>The metadata is only used for logging warnings about configuration mismatches
   *   <li>Worst case: duplicate "created rate limiter" log messages on first initialization
   *   <li>The rate limiter's actual configuration in Redis is always correct
   * </ul>
   */
  private void ensureRateLimiterInitialized(
      @NonNull String rateLimitKey,
      long permits,
      @NonNull Duration interval,
      @NonNull RateType rateType) {

    RateLimitConfig newConfig = new RateLimitConfig(permits, interval.toMillis(), rateType);
    RateLimitConfig existingConfig = initializedRateLimiters.get(rateLimitKey);

    if (existingConfig != null) {
      if (!existingConfig.equals(newConfig)) {
        LOG.warn(
            "Rate limiter [{}] is configured with different settings in this JVM: "
                + "existing={}, new={}. Using existing configuration.",
            rateLimitKey,
            existingConfig,
            newConfig);
      }
      return; // Already initialized by this JVM
    }

    String metaKey = rateLimitKey + META_SUFFIX;
    RBucket<RateLimitConfig> metaBucket = redissonClient.getBucket(metaKey);
    RRateLimiter rateLimiter = redissonClient.getRateLimiter(rateLimitKey);

    RateLimitConfig existingRedisConfig = metaBucket.get();

    if (existingRedisConfig == null) {
      // First time - create rate limiter and metadata
      org.redisson.api.RateType redissonRateType =
          rateType == RateType.OVERALL
              ? org.redisson.api.RateType.OVERALL
              : org.redisson.api.RateType.PER_CLIENT;

      boolean created = rateLimiter.trySetRate(redissonRateType, permits, interval);

      if (created) {
        metaBucket.set(newConfig);
        LOG.info(
            "Created rate limiter [{}] with permits={}, interval={}, type={}",
            rateLimitKey,
            permits,
            interval,
            rateType);
      } else {
        // Race condition: another instance created it between our check and set
        // Read the actual value and warn if different
        existingRedisConfig = metaBucket.get();
        if (existingRedisConfig != null && !existingRedisConfig.equals(newConfig)) {
          LOG.warn(
              "Rate limiter [{}] was created by another instance with different settings: "
                  + "existing={}, this={}. Using existing configuration. "
                  + "To change: delete Redis keys '{}' and '{}', then redeploy all instances.",
              rateLimitKey,
              existingRedisConfig,
              newConfig,
              rateLimitKey,
              metaKey);
        }
      }
    } else if (!existingRedisConfig.equals(newConfig)) {
      // Rate limiter exists with different configuration
      LOG.warn(
          "Rate limiter [{}] exists with different settings: existing={}, this={}. "
              + "Using existing configuration. To change: delete Redis keys '{}' and '{}', "
              + "then redeploy all instances.",
          rateLimitKey,
          existingRedisConfig,
          newConfig,
          rateLimitKey,
          metaKey);
    }

    initializedRateLimiters.put(
        rateLimitKey, existingRedisConfig != null ? existingRedisConfig : newConfig);
  }

  /**
   * Attempts to acquire a permit from the rate limiter.
   *
   * @return true if a permit was acquired, false otherwise
   */
  private boolean tryAcquirePermit(
      @NonNull RRateLimiter rateLimiter,
      @NonNull AcquisitionMode mode,
      @NonNull Duration waitTime) {
    return switch (mode) {
      case SKIP_IMMEDIATELY -> rateLimiter.tryAcquire();
      case WAIT_AND_SKIP -> rateLimiter.tryAcquire(1, waitTime);
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
   * instantiation on every rate limit breach. Handler instances are cached per class type and
   * reused across all invocations.
   *
   * <p>Handler resolution follows this order:
   *
   * <ol>
   *   <li>Look up the handler as a Spring bean from ApplicationContext by type
   *   <li>Fall back to reflection-based instantiation (requires public no-arg constructor)
   * </ol>
   *
   * @param handlerClass the handler class to instantiate
   * @return a cached or newly created instance of the handler
   * @throws IllegalStateException if the handler cannot be instantiated
   */
  @NonNull
  private RateLimitSkipHandler getHandlerInstance(
      @NonNull Class<? extends RateLimitSkipHandler> handlerClass) {
    return handlerCache.computeIfAbsent(
        handlerClass,
        clazz -> {
          // First, try to get the handler as a Spring bean (only if context is active)
          if (isApplicationContextActive()) {
            try {
              RateLimitSkipHandler bean = applicationContext.getBean(clazz);
              if (bean != null) {
                return bean;
              }
            } catch (BeansException ignored) {
              // Bean not found, will fall back to reflection
            }
          } else {
            if (Boolean.TRUE.equals(rateLimitProperties.debug())) {
              LOG.info(
                  "ApplicationContext is not active, skipping Spring bean lookup for handler: {}",
                  clazz.getName());
            }
          }
          // Not a Spring bean, fall back to reflection
          if (Boolean.TRUE.equals(rateLimitProperties.debug())) {
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
      @NonNull RateLimit annotation,
      @NonNull ProceedingJoinPoint joinPoint,
      @NonNull String rateLimitKey,
      @NonNull String methodName) {
    final RateLimitSkipHandler handler = getHandlerInstance(annotation.skipHandler());
    final MethodSignature signature = (MethodSignature) joinPoint.getSignature();
    final RateLimitContext context =
        new RateLimitContext(
            rateLimitKey,
            methodName,
            signature.getMethod(),
            joinPoint.getArgs(),
            signature.getReturnType());
    return handler.handle(context);
  }

  /**
   * Configuration record for metadata storage. Must be serializable for Redis storage.
   *
   * @param permits the number of permits per interval
   * @param intervalMs the interval in milliseconds
   * @param rateType the rate type (OVERALL or PER_CLIENT)
   */
  private record RateLimitConfig(long permits, long intervalMs, RateType rateType)
      implements Serializable {
    @Serial private static final long serialVersionUID = 1L;

    public RateLimitConfig {
      Objects.requireNonNull(rateType, "rateType must not be null");
    }

    @Override
    public String toString() {
      return "RateLimitConfig[permits="
          + permits
          + ", intervalMs="
          + intervalMs
          + ", rateType="
          + rateType
          + "]";
    }
  }
}
