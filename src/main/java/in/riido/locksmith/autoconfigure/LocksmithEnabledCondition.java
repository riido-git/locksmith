package in.riido.locksmith.autoconfigure;

import in.riido.locksmith.LocksmithConfigurationException;
import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches when {@code locksmith.enabled} is {@code true} or not set; {@link Disabled} matches when
 * it is {@code false}. Both ignore case. Any other value, an empty one included, fails the startup
 * with a {@link LocksmithConfigurationException} that names it, so a mistyped value cannot switch
 * Locksmith off without a word.
 */
class LocksmithEnabledCondition implements Condition {

  @Override
  public boolean matches(
      @NonNull ConditionContext context, @NonNull AnnotatedTypeMetadata metadata) {
    return enabled(context.getEnvironment());
  }

  /**
   * Reads {@code locksmith.enabled}.
   *
   * @throws LocksmithConfigurationException if it is set to anything but {@code true} or {@code
   *     false}, ignoring case
   */
  static boolean enabled(@NonNull Environment environment) {
    String value = environment.getProperty(LocksmithAutoConfiguration.ENABLED_PROPERTY);
    if (value == null || value.equalsIgnoreCase("true")) {
      return true;
    }
    if (value.equalsIgnoreCase("false")) {
      return false;
    }
    throw new LocksmithConfigurationException(
        LocksmithAutoConfiguration.ENABLED_PROPERTY
            + " must be true or false, got ["
            + value
            + "]");
  }

  /** Matches when {@code locksmith.enabled} is {@code false}, ignoring case. */
  static final class Disabled extends LocksmithEnabledCondition {

    @Override
    public boolean matches(
        @NonNull ConditionContext context, @NonNull AnnotatedTypeMetadata metadata) {
      return !enabled(context.getEnvironment());
    }
  }
}
