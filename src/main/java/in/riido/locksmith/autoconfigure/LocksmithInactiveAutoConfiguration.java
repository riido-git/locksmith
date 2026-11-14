package in.riido.locksmith.autoconfigure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Role;
import org.springframework.core.Ordered;

/**
 * Active when Locksmith is enabled but no {@code RedissonClient} bean exists: registers nothing and
 * logs one WARN, so an operator sees that annotated methods run without coordination. Ordered after
 * the Redisson starter, so its client counts; the type is named as a string, so the WARN also comes
 * when Redisson is not on the classpath at all. Ordered after the other auto-configurations, so a
 * {@code RedissonClient} bean that any of them defines is seen, whatever its class is called.
 * Infrastructure role, so lazy initialization still creates it and the WARN is logged.
 */
@AutoConfiguration(afterName = "org.redisson.spring.starter.RedissonAutoConfigurationV4")
@AutoConfigureOrder(Ordered.LOWEST_PRECEDENCE)
@Conditional(LocksmithEnabledCondition.class)
@ConditionalOnMissingBean(type = "org.redisson.api.RedissonClient")
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
public class LocksmithInactiveAutoConfiguration {

  /** The WARN logged at startup. */
  static final String INACTIVE_WARNING =
      "Locksmith is inactive: there is no RedissonClient bean, so annotated methods run without"
          + " any coordination";

  private static final Logger LOG =
      LoggerFactory.getLogger(LocksmithInactiveAutoConfiguration.class);

  /** Creates the configuration and logs the inactive WARN. */
  public LocksmithInactiveAutoConfiguration() {
    LOG.warn(INACTIVE_WARNING);
  }
}
