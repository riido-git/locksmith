package in.riido.locksmith.aspect;

import in.riido.locksmith.AcquisitionMode;
import in.riido.locksmith.RateLimit;
import in.riido.locksmith.autoconfigure.LocksmithProperties;
import in.riido.locksmith.autoconfigure.LocksmithProperties.RateLimitProperties;
import in.riido.locksmith.exception.RateLimitConfigurationException;
import in.riido.locksmith.handler.RateLimitSkipHandler;
import in.riido.locksmith.metrics.RateLimitMetrics;
import in.riido.locksmith.models.RateLimitContext;
import in.riido.locksmith.support.AspectSupport;
import in.riido.locksmith.support.DurationResolver;
import in.riido.locksmith.support.RateLimitInitializer;
import in.riido.locksmith.support.SpELKeyResolver;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
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

  private final RateLimitInitializer rateLimitInitializer;

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
    this.rateLimitInitializer = new RateLimitInitializer(redissonClient);
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
    final boolean debugMode = rateLimitProperties.debug();
    final String methodName = AspectSupport.formatMethodSignature(joinPoint);

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
      throw new RateLimitConfigurationException(
          String.format(
              "RateLimit permits must be positive on method [%s], got: %d",
              methodName, annotation.permits()),
          annotation.key());
    }

    final String resolvedKey = SpELKeyResolver.resolve(annotation.key(), joinPoint);
    final String rateLimitKey = rateLimitProperties.keyPrefix() + resolvedKey;
    final long permits = annotation.permits();

    // Parse interval
    final Duration interval =
        DurationResolver.resolve(annotation.interval(), Duration.ofSeconds(1));

    // Validate interval is positive
    if (interval.isZero() || interval.isNegative()) {
      throw new RateLimitConfigurationException(
          String.format(
              "RateLimit interval must be positive on method [%s], got: %s", methodName, interval),
          annotation.key());
    }

    final Duration waitTime =
        DurationResolver.resolve(annotation.waitTime(), rateLimitProperties.waitTime());

    // Initialize rate limiter in Redis (first time only per key per JVM)
    rateLimitInitializer.ensureInitialized(rateLimitKey, permits, interval, annotation.type());

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
        rateLimitMetrics.recordExceeded(annotation.mode());
      }
      if (debugMode) {
        LOG.info(
            "Rate limit exceeded for [{}] in [{}], invoking skip handler: {}",
            rateLimitKey,
            methodName,
            annotation.skipHandler().getSimpleName());
      } else {
        LOG.info("Skipping execution of [{}] - rate limit [{}] exceeded", methodName, rateLimitKey);
      }
      return handleSkip(annotation, joinPoint, rateLimitKey, methodName);
    }

    if (rateLimitMetrics != null) {
      rateLimitMetrics.recordAcquisitionTime(System.currentTimeMillis() - acquisitionStartTime);
      rateLimitMetrics.recordAcquired();
    }

    LOG.info("Rate limit permit acquired for [{}] in [{}]", rateLimitKey, methodName);

    final long startTime = System.currentTimeMillis();
    try {
      final Object result = joinPoint.proceed();

      if (debugMode) {
        final long executionTime = System.currentTimeMillis() - startTime;
        LOG.info(
            "Method [{}] executed in {}ms, returnType={}, hasResult={}",
            methodName,
            executionTime,
            signature.getReturnType().getSimpleName(),
            result != null);
      }

      return result;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw e;
    } finally {
      if (rateLimitMetrics != null) {
        rateLimitMetrics.recordExecutionTime(System.currentTimeMillis() - startTime);
      }
      LOG.info("Rate limit execution completed for [{}] in [{}]", rateLimitKey, methodName);
    }
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
  private RateLimitSkipHandler getHandlerInstance(
      @NonNull Class<? extends RateLimitSkipHandler> handlerClass) {
    return handlerCache.computeIfAbsent(
        handlerClass,
        clazz ->
            AspectSupport.resolveHandler(clazz, applicationContext, rateLimitProperties.debug()));
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
}
