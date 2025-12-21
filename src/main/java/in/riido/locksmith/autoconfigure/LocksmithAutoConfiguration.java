package in.riido.locksmith.autoconfigure;

import in.riido.locksmith.aspect.DistributedLockAspect;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Autoconfiguration for Locksmith distributed locking.
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
   * @return the configured DistributedLockAspect
   */
  @Bean
  @ConditionalOnMissingBean
  public DistributedLockAspect distributedLockAspect(
      RedissonClient redissonClient, LocksmithProperties properties) {
    LOG.info("Initializing locksmith with properties: {}", properties);
    return new DistributedLockAspect(redissonClient, properties);
  }
}
