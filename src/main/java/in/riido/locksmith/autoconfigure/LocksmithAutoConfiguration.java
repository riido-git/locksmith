package in.riido.locksmith.autoconfigure;

import in.riido.locksmith.aop.LocksmithAdvisor;
import in.riido.locksmith.aop.LocksmithAutoProxyRegistrar;
import in.riido.locksmith.aop.LocksmithInterceptor;
import in.riido.locksmith.aop.MethodSpecFactory;
import in.riido.locksmith.lock.LockOperations;
import in.riido.locksmith.metrics.LocksmithMetrics;
import in.riido.locksmith.metrics.MicrometerLocksmithMetrics;
import in.riido.locksmith.metrics.NoOpLocksmithMetrics;
import in.riido.locksmith.semaphore.SemaphoreOperations;
import in.riido.locksmith.support.AnnotationValidator;
import io.micrometer.core.instrument.MeterRegistry;
import org.jspecify.annotations.NonNull;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Role;
import org.springframework.core.env.Environment;

/**
 * Registers Locksmith when a {@link RedissonClient} bean exists and {@code locksmith.enabled} is
 * not {@code false}: the operations, the advisor that applies the annotations, the startup
 * validator, and an auto-proxy creator if the context has none. Logs one INFO line with the
 * effective settings.
 */
@AutoConfiguration(
    afterName = {
      "org.redisson.spring.starter.RedissonAutoConfigurationV2",
      "org.springframework.boot.micrometer.metrics.autoconfigure"
          + ".CompositeMeterRegistryAutoConfiguration"
    })
@ConditionalOnClass(RedissonClient.class)
@ConditionalOnBean(RedissonClient.class)
@ConditionalOnProperty(
    name = LocksmithAutoConfiguration.ENABLED_PROPERTY,
    havingValue = "true",
    matchIfMissing = true)
@EnableConfigurationProperties(LocksmithProperties.class)
@Import(LocksmithAutoProxyRegistrar.class)
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
public class LocksmithAutoConfiguration implements SmartInitializingSingleton {

  /** Property that switches Locksmith off when {@code false}. */
  static final String ENABLED_PROPERTY = "locksmith.enabled";

  private static final Logger LOG = LoggerFactory.getLogger(LocksmithAutoConfiguration.class);

  private static final String UNKNOWN_VERSION = "unknown";

  /**
   * Registers the Micrometer metrics when a {@link MeterRegistry} bean exists. Declared first, so
   * its bean is registered before the no-op fallback is considered.
   */
  @Configuration(proxyBeanMethods = false)
  @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
  @ConditionalOnClass(MeterRegistry.class)
  @ConditionalOnBean(MeterRegistry.class)
  static class MicrometerConfiguration {

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    @NonNull LocksmithMetrics micrometerLocksmithMetrics(@NonNull MeterRegistry registry) {
      return new MicrometerLocksmithMetrics(registry);
    }
  }

  private final @NonNull ObjectProvider<LocksmithProperties> properties;

  /**
   * Creates the configuration. The properties are resolved only in {@link
   * #afterSingletonsInstantiated()}: this class is created while bean post-processors are still
   * being registered, and resolving them here would create the properties bean too early.
   *
   * @param properties the bound properties, resolved lazily
   */
  public LocksmithAutoConfiguration(@NonNull ObjectProvider<LocksmithProperties> properties) {
    this.properties = properties;
  }

  /**
   * Logs the effective settings with the Spring Boot and Redisson versions, once per application
   * context, after every singleton has been created.
   */
  @Override
  public void afterSingletonsInstantiated() {
    LocksmithProperties bound = properties.getObject();
    String redissonVersion = RedissonClient.class.getPackage().getImplementationVersion();
    LOG.info(
        "Locksmith enabled: key-prefix [{}], semaphore lease-time {}, Spring Boot {}, Redisson {}",
        bound.keyPrefix(),
        bound.semaphore().leaseTime(),
        SpringBootVersion.getVersion(),
        redissonVersion == null ? UNKNOWN_VERSION : redissonVersion);
  }

  /**
   * The lock operations.
   *
   * @param redisson the adopter's client
   * @param properties the bound properties
   * @param metrics the metrics
   * @return the operations
   */
  @Bean
  @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
  @ConditionalOnMissingBean
  public @NonNull LockOperations lockOperations(
      @NonNull RedissonClient redisson,
      @NonNull LocksmithProperties properties,
      @NonNull LocksmithMetrics metrics) {
    return new LockOperations(redisson, properties, metrics);
  }

  /**
   * The semaphore operations.
   *
   * @param redisson the adopter's client
   * @param properties the bound properties
   * @param metrics the metrics
   * @return the operations
   */
  @Bean
  @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
  @ConditionalOnMissingBean
  public @NonNull SemaphoreOperations semaphoreOperations(
      @NonNull RedissonClient redisson,
      @NonNull LocksmithProperties properties,
      @NonNull LocksmithMetrics metrics) {
    return new SemaphoreOperations(redisson, properties, metrics);
  }

  /**
   * The interceptor that runs annotated methods under their permit and lock.
   *
   * @param lockOperations the lock operations, resolved lazily
   * @param semaphoreOperations the semaphore operations, resolved lazily
   * @param methodSpecFactory builds method specs, resolved lazily
   * @param beanFactory resolves failure handler beans
   * @return the interceptor
   */
  @Bean
  @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
  public @NonNull LocksmithInterceptor locksmithInterceptor(
      @NonNull ObjectProvider<LockOperations> lockOperations,
      @NonNull ObjectProvider<SemaphoreOperations> semaphoreOperations,
      @NonNull ObjectProvider<MethodSpecFactory> methodSpecFactory,
      @NonNull BeanFactory beanFactory) {
    return new LocksmithInterceptor(
        lockOperations, semaphoreOperations, methodSpecFactory, beanFactory);
  }

  /**
   * The advisor that applies the interceptor to annotated methods. Infrastructure role, because
   * without AspectJ the auto-proxy creator, Spring Boot's or the one {@link
   * LocksmithAutoProxyRegistrar} registers, is an {@code InfrastructureAdvisorAutoProxyCreator},
   * which applies only infrastructure advisors.
   *
   * @param interceptor the interceptor
   * @return the advisor
   */
  @Bean
  @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
  public @NonNull LocksmithAdvisor locksmithAdvisor(@NonNull LocksmithInterceptor interceptor) {
    return new LocksmithAdvisor(interceptor);
  }

  /**
   * The factory that reads and checks the annotations.
   *
   * @param environment resolves placeholders
   * @param properties the bound properties
   * @return the factory
   */
  @Bean
  @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
  public @NonNull MethodSpecFactory methodSpecFactory(
      @NonNull Environment environment, @NonNull LocksmithProperties properties) {
    return new MethodSpecFactory(environment, properties);
  }

  /**
   * The startup validator. Static, so it is created before ordinary beans without creating this
   * configuration.
   *
   * @param interceptor the interceptor, resolved lazily
   * @param methodSpecFactory the factory, resolved lazily
   * @param beanFactory counts handler beans
   * @return the validator
   */
  @Bean
  @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
  public static @NonNull AnnotationValidator annotationValidator(
      @NonNull ObjectProvider<LocksmithInterceptor> interceptor,
      @NonNull ObjectProvider<MethodSpecFactory> methodSpecFactory,
      @NonNull ListableBeanFactory beanFactory) {
    return new AnnotationValidator(interceptor, methodSpecFactory, beanFactory);
  }

  /**
   * The no-op metrics, used when no Micrometer registry exists.
   *
   * @return the no-op metrics
   */
  @Bean
  @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
  @ConditionalOnMissingBean(LocksmithMetrics.class)
  public @NonNull LocksmithMetrics noOpLocksmithMetrics() {
    return new NoOpLocksmithMetrics();
  }
}
