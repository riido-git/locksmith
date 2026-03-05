package in.riido.locksmith.template;

import in.riido.locksmith.RateType;
import in.riido.locksmith.autoconfigure.LocksmithProperties;
import in.riido.locksmith.autoconfigure.LocksmithProperties.RateLimitProperties;
import in.riido.locksmith.exception.RateLimitConfigurationException;
import in.riido.locksmith.metrics.RateLimitMetrics;
import in.riido.locksmith.support.RateLimitConfig;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Template class for programmatic distributed rate limiting operations.
 *
 * <p>This class provides a builder-based programmatic API for distributed rate limiting,
 * complementing the annotation-based approach provided by {@link in.riido.locksmith.RateLimit}. Use
 * this when you need more control over rate limit checking, or when annotations are not suitable.
 *
 * <p>All methods apply the configured key prefix automatically. For example, if the key prefix is
 * "ratelimit:" and you use {@code withKey("api-call")}, the actual Redis key will be
 * "ratelimit:api-call".
 *
 * <h2>Simple check</h2>
 *
 * <pre>{@code
 * if (rateLimitTemplate.withKey("api-call").tryAcquire()) {
 *     // Execute operation
 * }
 * }</pre>
 *
 * <h2>Callback-based</h2>
 *
 * <pre>{@code
 * String result = rateLimitTemplate.withKey("api-call")
 *     .execute(() -> apiClient.call());
 * }</pre>
 *
 * <h2>Builder with custom configuration</h2>
 *
 * <pre>{@code
 * // Custom rate: 100 requests per minute
 * if (rateLimitTemplate.withKey("heavy-operation")
 *         .permits(100)
 *         .interval(Duration.ofMinutes(1))
 *         .tryAcquire()) {
 *     // Execute operation
 * }
 *
 * // Execute with custom rate and wait time
 * String result = rateLimitTemplate.withKey("throttled-api")
 *     .permits(10)
 *     .interval(Duration.ofSeconds(1))
 *     .waitTime(Duration.ofSeconds(5))
 *     .execute(() -> apiClient.call());
 * }</pre>
 *
 * @author Garvit Joshi
 * @since 3.0.0
 * @see RateLimitCallback
 * @see RateLimitOperationBuilder
 * @see in.riido.locksmith.RateLimit
 */
public class LocksmithRateLimitTemplate {

  private static final Logger LOG = LoggerFactory.getLogger(LocksmithRateLimitTemplate.class);

  private static final long DEFAULT_PERMITS = 10;
  private static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(1);

  private final RedissonClient redissonClient;
  private final RateLimitProperties rateLimitProperties;
  @Nullable private final RateLimitMetrics rateLimitMetrics;

  /** Cache to track rate limiter configurations per key within this JVM. */
  private final Map<String, RateLimitConfig> initializedConfigs = new ConcurrentHashMap<>();

  /**
   * Constructs a new LocksmithRateLimitTemplate.
   *
   * @param redissonClient the Redisson client for Redis operations
   * @param properties the configuration properties
   */
  public LocksmithRateLimitTemplate(
      @NonNull RedissonClient redissonClient, @NonNull LocksmithProperties properties) {
    this(redissonClient, properties, null);
  }

  /**
   * Constructs a new LocksmithRateLimitTemplate with optional metrics support.
   *
   * @param redissonClient the Redisson client for Redis operations
   * @param properties the configuration properties
   * @param rateLimitMetrics the optional rate limit metrics for observability
   */
  public LocksmithRateLimitTemplate(
      @NonNull RedissonClient redissonClient,
      @NonNull LocksmithProperties properties,
      @Nullable RateLimitMetrics rateLimitMetrics) {
    this.redissonClient = redissonClient;
    this.rateLimitProperties = properties.rateLimit();
    this.rateLimitMetrics = rateLimitMetrics;
  }

  // ========== Builder Entry Point ==========

  /**
   * Creates a builder for rate limit operations on the specified key.
   *
   * <pre>{@code
   * // Custom rate: 100 requests per minute
   * boolean acquired = rateLimitTemplate.withKey("heavy-operation")
   *     .permits(100)
   *     .interval(Duration.ofMinutes(1))
   *     .tryAcquire();
   *
   * // Execute with custom rate
   * String result = rateLimitTemplate.withKey("api-call")
   *     .permits(20)
   *     .interval(Duration.ofSeconds(1))
   *     .execute(() -> apiClient.call());
   * }</pre>
   *
   * @param key the rate limiter key (prefix will be applied automatically)
   * @return a builder for configuring and executing rate limit operations
   */
  @NonNull
  public RateLimitOperationBuilder withKey(@NonNull String key) {
    return new RateLimitOperationBuilder(key);
  }

  // ========== Internal Methods ==========

  private boolean doTryAcquire(
      @NonNull String key,
      long permits,
      @NonNull Duration interval,
      @NonNull RateType rateType,
      @NonNull Duration waitTime,
      boolean immediateMode) {
    if (permits <= 0) {
      throw new RateLimitConfigurationException("Permits must be positive, got: " + permits, key);
    }

    String fullKey = rateLimitProperties.keyPrefix() + key;
    RRateLimiter rateLimiter = getOrCreateRateLimiter(fullKey, permits, interval, rateType);
    long startTime = System.currentTimeMillis();

    boolean acquired =
        waitTime.isZero() ? rateLimiter.tryAcquire() : rateLimiter.tryAcquire(1, waitTime);

    if (acquired) {
      if (rateLimitMetrics != null) {
        rateLimitMetrics.recordAcquisitionTime(System.currentTimeMillis() - startTime);
        rateLimitMetrics.recordAcquired();
      }
      LOG.debug("Rate limit permit acquired for [{}]", fullKey);
    } else {
      if (rateLimitMetrics != null) {
        String reason = immediateMode ? "immediate" : "timeout";
        rateLimitMetrics.recordExceeded(reason);
      }
      LOG.debug("Rate limit exceeded for [{}]", fullKey);
    }
    return acquired;
  }

  @Nullable
  private <T> T doExecute(
      @NonNull String key,
      long permits,
      @NonNull Duration interval,
      @NonNull RateType rateType,
      @NonNull Duration waitTime,
      boolean immediateMode,
      @NonNull RateLimitCallback<T> callback)
      throws Exception {
    if (permits <= 0) {
      throw new RateLimitConfigurationException("Permits must be positive, got: " + permits, key);
    }

    String fullKey = rateLimitProperties.keyPrefix() + key;
    RRateLimiter rateLimiter = getOrCreateRateLimiter(fullKey, permits, interval, rateType);
    long acquisitionStartTime = System.currentTimeMillis();

    boolean acquired =
        waitTime.isZero() ? rateLimiter.tryAcquire() : rateLimiter.tryAcquire(1, waitTime);

    if (!acquired) {
      if (rateLimitMetrics != null) {
        String reason = immediateMode ? "immediate" : "timeout";
        rateLimitMetrics.recordExceeded(reason);
      }
      LOG.debug("Rate limit exceeded for [{}], callback not executed", fullKey);
      return null;
    }

    if (rateLimitMetrics != null) {
      rateLimitMetrics.recordAcquisitionTime(System.currentTimeMillis() - acquisitionStartTime);
      rateLimitMetrics.recordAcquired();
    }

    LOG.debug("Rate limit permit acquired for [{}], executing callback", fullKey);
    long executionStartTime = System.currentTimeMillis();
    try {
      return callback.execute();
    } finally {
      if (rateLimitMetrics != null) {
        rateLimitMetrics.recordExecutionTime(System.currentTimeMillis() - executionStartTime);
      }
    }
  }

  /**
   * Gets or creates a rate limiter in Redis with the configured rate.
   *
   * <p>This method is thread-safe and will only initialize each rate limiter once per JVM. It also
   * validates that the same key is not used with different configurations within this JVM.
   *
   * @param fullKey the full rate limiter key including prefix
   * @param permits the number of permits per interval
   * @param interval the interval duration
   * @param rateType the rate type (OVERALL or PER_CLIENT)
   * @return the rate limiter instance
   */
  @NonNull
  private RRateLimiter getOrCreateRateLimiter(
      @NonNull String fullKey,
      long permits,
      @NonNull Duration interval,
      @NonNull RateType rateType) {
    RRateLimiter rateLimiter = redissonClient.getRateLimiter(fullKey);

    RateLimitConfig newConfig = new RateLimitConfig(permits, interval.toMillis(), rateType);
    RateLimitConfig existingConfig = initializedConfigs.get(fullKey);

    if (existingConfig != null) {
      if (!existingConfig.equals(newConfig)) {
        LOG.warn(
            "Rate limiter [{}] is configured with different settings in this JVM: "
                + "existing={}, new={}. Using existing configuration.",
            fullKey,
            existingConfig,
            newConfig);
      }
      return rateLimiter;
    }

    org.redisson.api.RateType redissonRateType =
        rateType == RateType.OVERALL
            ? org.redisson.api.RateType.OVERALL
            : org.redisson.api.RateType.PER_CLIENT;

    boolean created = rateLimiter.trySetRate(redissonRateType, permits, interval);

    if (created) {
      LOG.info(
          "Created rate limiter [{}] with permits={}, interval={}, type={}",
          fullKey,
          permits,
          interval,
          rateType);
    } else {
      LOG.debug("Rate limiter [{}] already exists, using existing configuration", fullKey);
    }

    initializedConfigs.put(fullKey, newConfig);
    return rateLimiter;
  }

  // ========== Builder Class ==========

  /**
   * Builder for configuring and executing rate limit operations.
   *
   * <p>This builder provides a fluent API for rate limit operations with custom configuration. Use
   * {@link LocksmithRateLimitTemplate#withKey(String)} to create an instance.
   *
   * <p>Example usage:
   *
   * <pre>{@code
   * // Custom rate: 100 requests per minute
   * boolean acquired = rateLimitTemplate.withKey("heavy-operation")
   *     .permits(100)
   *     .interval(Duration.ofMinutes(1))
   *     .tryAcquire();
   *
   * // Execute with custom rate and wait time
   * String result = rateLimitTemplate.withKey("throttled-api")
   *     .permits(10)
   *     .interval(Duration.ofSeconds(1))
   *     .waitTime(Duration.ofSeconds(5))
   *     .execute(() -> apiClient.call());
   * }</pre>
   *
   * @since 3.0.0
   */
  public class RateLimitOperationBuilder {

    private final String key;
    private long permits = DEFAULT_PERMITS;
    private Duration interval = DEFAULT_INTERVAL;
    private RateType rateType = RateType.OVERALL;
    private Duration waitTime = Duration.ZERO;

    private RateLimitOperationBuilder(@NonNull String key) {
      this.key = key;
    }

    /**
     * Sets the number of permits allowed per interval.
     *
     * <p>Default is 10.
     *
     * @param permits the number of permits per interval
     * @return this builder for chaining
     */
    @NonNull
    public RateLimitOperationBuilder permits(long permits) {
      this.permits = permits;
      return this;
    }

    /**
     * Sets the interval for the rate limit.
     *
     * <p>Default is 1 second (permits per second).
     *
     * @param interval the interval duration
     * @return this builder for chaining
     */
    @NonNull
    public RateLimitOperationBuilder interval(@NonNull Duration interval) {
      this.interval = interval;
      return this;
    }

    /**
     * Sets the rate limit type.
     *
     * <p>Default is {@link RateType#OVERALL}.
     *
     * @param rateType the rate type (OVERALL or PER_CLIENT)
     * @return this builder for chaining
     */
    @NonNull
    public RateLimitOperationBuilder rateType(@NonNull RateType rateType) {
      this.rateType = rateType;
      return this;
    }

    /**
     * Sets the maximum time to wait for a permit.
     *
     * <p>Default is {@link Duration#ZERO} (no waiting, immediate check).
     *
     * @param waitTime the maximum wait time
     * @return this builder for chaining
     */
    @NonNull
    public RateLimitOperationBuilder waitTime(@NonNull Duration waitTime) {
      this.waitTime = waitTime;
      return this;
    }

    /**
     * Tries to acquire a rate limit permit with the configured settings.
     *
     * @return true if a permit was acquired, false if rate limit exceeded
     */
    public boolean tryAcquire() {
      boolean immediateMode = waitTime.isZero();
      return doTryAcquire(key, permits, interval, rateType, waitTime, immediateMode);
    }

    /**
     * Executes a callback after acquiring a rate limit permit with the configured settings.
     *
     * @param <T> the type of result returned by the callback
     * @param callback the callback to execute after permit acquisition
     * @return the result of the callback, or null if rate limit exceeded
     * @throws Exception if the callback throws an exception
     */
    @Nullable
    public <T> T execute(@NonNull RateLimitCallback<T> callback) throws Exception {
      boolean immediateMode = waitTime.isZero();
      return doExecute(key, permits, interval, rateType, waitTime, immediateMode, callback);
    }
  }
}
