package in.riido.locksmith.autoconfigure;

import in.riido.locksmith.metrics.LockMetrics;
import in.riido.locksmith.metrics.RateLimitMetrics;
import in.riido.locksmith.metrics.SemaphoreMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for Locksmith metrics integration with Micrometer.
 *
 * <p>This configuration is automatically applied when:
 *
 * <ul>
 *   <li>Micrometer's {@link MeterRegistry} is on the classpath
 *   <li>A {@link MeterRegistry} bean is available
 *   <li>The respective {@code metrics-enabled} property is set to {@code true}
 * </ul>
 *
 * <p>Configuration example:
 *
 * <pre>{@code
 * locksmith:
 *   lock:
 *     metrics-enabled: true
 *   semaphore:
 *     metrics-enabled: true
 *   rate-limit:
 *     metrics-enabled: true
 * }</pre>
 *
 * @author Garvit Joshi
 * @since 2.1.0
 * @see LockMetrics
 * @see SemaphoreMetrics
 * @see RateLimitMetrics
 */
@AutoConfiguration
@AutoConfigureBefore(LocksmithAutoConfiguration.class)
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnBean(MeterRegistry.class)
@EnableConfigurationProperties(LocksmithProperties.class)
public class LocksmithMetricsAutoConfiguration {

  private static final Logger LOG =
      LoggerFactory.getLogger(LocksmithMetricsAutoConfiguration.class);

  /** Default constructor. */
  public LocksmithMetricsAutoConfiguration() {}

  /**
   * Creates the lock metrics bean.
   *
   * @param registry the Micrometer registry
   * @return the configured LockMetrics
   */
  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnProperty(name = "locksmith.lock.metrics-enabled", havingValue = "true")
  @NonNull
  public LockMetrics lockMetrics(@NonNull MeterRegistry registry) {
    LOG.info("Initializing locksmith lock metrics");
    return new LockMetrics(registry);
  }

  /**
   * Creates the semaphore metrics bean.
   *
   * @param registry the Micrometer registry
   * @return the configured SemaphoreMetrics
   */
  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnProperty(name = "locksmith.semaphore.metrics-enabled", havingValue = "true")
  @NonNull
  public SemaphoreMetrics semaphoreMetrics(@NonNull MeterRegistry registry) {
    LOG.info("Initializing locksmith semaphore metrics");
    return new SemaphoreMetrics(registry);
  }

  /**
   * Creates the rate limit metrics bean.
   *
   * @param registry the Micrometer registry
   * @return the configured RateLimitMetrics
   * @since 3.0.0
   */
  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnProperty(name = "locksmith.rate-limit.metrics-enabled", havingValue = "true")
  @NonNull
  public RateLimitMetrics rateLimitMetrics(@NonNull MeterRegistry registry) {
    LOG.info("Initializing locksmith rate limit metrics");
    return new RateLimitMetrics(registry);
  }
}
