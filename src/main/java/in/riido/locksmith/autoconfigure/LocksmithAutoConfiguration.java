package in.riido.locksmith.autoconfigure;

import in.riido.locksmith.aspect.DistributedLockAspect;
import in.riido.locksmith.aspect.DistributedSemaphoreAspect;
import in.riido.locksmith.metrics.LockMetrics;
import in.riido.locksmith.metrics.SemaphoreMetrics;
import in.riido.locksmith.template.LocksmithLockTemplate;
import in.riido.locksmith.template.LocksmithSemaphoreTemplate;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * Autoconfiguration for Locksmith distributed locking and semaphore support.
 *
 * <p>This configuration is automatically applied when:
 *
 * <ul>
 *   <li>Redisson classes are on the classpath
 *   <li>A {@link RedissonClient} bean is available
 * </ul>
 *
 * <p>The user must provide their own {@link RedissonClient} bean. This starter does not
 * autoconfigure Redis connections, giving users full control over their Redis setup.
 *
 * <p>Usage in your application:
 *
 * <pre>{@code
 * @Configuration
 * public class RedisConfig {
 *     @Bean
 *     public RedissonClient redissonClient() {
 *         Config config = new Config();
 *         config.useSingleServer().setAddress("redis://localhost:6379");
 *         return Redisson.create(config);
 *     }
 * }
 * }</pre>
 *
 * @author Garvit Joshi
 * @since 1.0.0
 */
@AutoConfiguration
@ConditionalOnClass(RedissonClient.class)
@ConditionalOnBean(RedissonClient.class)
@EnableConfigurationProperties(LocksmithProperties.class)
public class LocksmithAutoConfiguration {

  private static final Logger LOG = LoggerFactory.getLogger(LocksmithAutoConfiguration.class);

  /** Default constructor. */
  public LocksmithAutoConfiguration() {}

  /**
   * Creates the distributed lock aspect bean.
   *
   * @param redissonClient the Redisson client (must be provided by the user)
   * @param properties the locksmith configuration properties
   * @param applicationContext the Spring application context for handler bean lookup
   * @param lockMetricsProvider optional lock metrics provider for observability
   * @return the configured DistributedLockAspect
   */
  @Bean
  @ConditionalOnMissingBean
  @NonNull
  public DistributedLockAspect distributedLockAspect(
      @NonNull RedissonClient redissonClient,
      @NonNull LocksmithProperties properties,
      @NonNull ApplicationContext applicationContext,
      @NonNull ObjectProvider<LockMetrics> lockMetricsProvider) {
    String redissonVersion = RedissonClient.class.getPackage().getImplementationVersion();
    String springBootVersion = SpringBootVersion.getVersion();
    @Nullable LockMetrics lockMetrics = lockMetricsProvider.getIfAvailable();
    LOG.info(
        "Initializing locksmith lock aspect with Spring Boot {} and Redisson {} - Lock Properties: {}, Metrics: {}",
        springBootVersion,
        redissonVersion,
        properties.lock(),
        lockMetrics != null ? "enabled" : "disabled");
    return new DistributedLockAspect(redissonClient, properties, applicationContext, lockMetrics);
  }

  /**
   * Creates the distributed semaphore aspect bean.
   *
   * @param redissonClient the Redisson client (must be provided by the user)
   * @param properties the locksmith configuration properties
   * @param applicationContext the Spring application context for handler bean lookup
   * @param semaphoreMetricsProvider optional semaphore metrics provider for observability
   * @return the configured DistributedSemaphoreAspect
   * @since 2.0.0
   */
  @Bean
  @ConditionalOnMissingBean
  @NonNull
  public DistributedSemaphoreAspect distributedSemaphoreAspect(
      @NonNull RedissonClient redissonClient,
      @NonNull LocksmithProperties properties,
      @NonNull ApplicationContext applicationContext,
      @NonNull ObjectProvider<SemaphoreMetrics> semaphoreMetricsProvider) {
    String redissonVersion = RedissonClient.class.getPackage().getImplementationVersion();
    String springBootVersion = SpringBootVersion.getVersion();
    @Nullable SemaphoreMetrics semaphoreMetrics = semaphoreMetricsProvider.getIfAvailable();
    LOG.info(
        "Initializing locksmith semaphore aspect with Spring Boot {} and Redisson {} - Semaphore Properties: {}, Metrics: {}",
        springBootVersion,
        redissonVersion,
        properties.semaphore(),
        semaphoreMetrics != null ? "enabled" : "disabled");
    return new DistributedSemaphoreAspect(
        redissonClient, properties, applicationContext, semaphoreMetrics);
  }

  /**
   * Creates the lock template bean for programmatic lock access.
   *
   * @param redissonClient the Redisson client (must be provided by the user)
   * @param properties the locksmith configuration properties
   * @param lockMetricsProvider optional lock metrics provider for observability
   * @return the configured LocksmithLockTemplate
   * @since 2.1.0
   */
  @Bean
  @ConditionalOnMissingBean
  @NonNull
  public LocksmithLockTemplate locksmithLockTemplate(
      @NonNull RedissonClient redissonClient,
      @NonNull LocksmithProperties properties,
      @NonNull ObjectProvider<LockMetrics> lockMetricsProvider) {
    @Nullable LockMetrics lockMetrics = lockMetricsProvider.getIfAvailable();
    LOG.info(
        "Initializing locksmith lock template, Metrics: {}",
        lockMetrics != null ? "enabled" : "disabled");
    return new LocksmithLockTemplate(redissonClient, properties, lockMetrics);
  }

  /**
   * Creates the semaphore template bean for programmatic semaphore access.
   *
   * @param redissonClient the Redisson client (must be provided by the user)
   * @param properties the locksmith configuration properties
   * @param semaphoreMetricsProvider optional semaphore metrics provider for observability
   * @return the configured LocksmithSemaphoreTemplate
   * @since 2.1.0
   */
  @Bean
  @ConditionalOnMissingBean
  @NonNull
  public LocksmithSemaphoreTemplate locksmithSemaphoreTemplate(
      @NonNull RedissonClient redissonClient,
      @NonNull LocksmithProperties properties,
      @NonNull ObjectProvider<SemaphoreMetrics> semaphoreMetricsProvider) {
    @Nullable SemaphoreMetrics semaphoreMetrics = semaphoreMetricsProvider.getIfAvailable();
    LOG.info(
        "Initializing locksmith semaphore template, Metrics: {}",
        semaphoreMetrics != null ? "enabled" : "disabled");
    return new LocksmithSemaphoreTemplate(redissonClient, properties, semaphoreMetrics);
  }
}
