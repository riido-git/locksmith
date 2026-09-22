package in.riido.locksmith.autoconfigure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * Active when {@code locksmith.enabled=false}: registers nothing and logs one WARN, so an operator
 * sees that annotated methods run without coordination.
 */
@AutoConfiguration
@ConditionalOnProperty(name = LocksmithAutoConfiguration.ENABLED_PROPERTY, havingValue = "false")
public class LocksmithDisabledAutoConfiguration {

  /** The WARN logged at startup. */
  static final String DISABLED_WARNING =
      "Locksmith is disabled: annotated methods run without any coordination";

  private static final Logger LOG =
      LoggerFactory.getLogger(LocksmithDisabledAutoConfiguration.class);

  /** Creates the configuration and logs the disabled WARN. */
  public LocksmithDisabledAutoConfiguration() {
    LOG.warn(DISABLED_WARNING);
  }
}
