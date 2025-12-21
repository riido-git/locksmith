package in.riido.locksmith.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import in.riido.locksmith.aspect.DistributedLockAspect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@DisplayName("LocksmithAutoConfiguration Tests")
class LocksmithAutoConfigurationTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(LocksmithAutoConfiguration.class));

  @Nested
  @DisplayName("Bean Creation Tests")
  class BeanCreationTests {

    @Test
    @DisplayName("Should create DistributedLockAspect when RedissonClient is present")
    void shouldCreateDistributedLockAspectWhenRedissonClientPresent() {
      contextRunner
          .withUserConfiguration(RedissonClientConfiguration.class)
          .run(
              context -> {
                assertThat(context).hasSingleBean(DistributedLockAspect.class);
              });
    }

    @Test
    @DisplayName("Should create LocksmithProperties bean")
    void shouldCreateLocksmithPropertiesBean() {
      contextRunner
          .withUserConfiguration(RedissonClientConfiguration.class)
          .run(
              context -> {
                assertThat(context).hasSingleBean(LocksmithProperties.class);
              });
    }

    @Test
    @DisplayName("Should not create beans when RedissonClient is missing")
    void shouldNotCreateBeansWhenRedissonClientMissing() {
      contextRunner.run(
          context -> {
            assertThat(context).doesNotHaveBean(DistributedLockAspect.class);
          });
    }
  }

  @Nested
  @DisplayName("Conditional Bean Tests")
  class ConditionalBeanTests {

    @Test
    @DisplayName("Should not override existing DistributedLockAspect bean")
    void shouldNotOverrideExistingDistributedLockAspectBean() {
      contextRunner
          .withUserConfiguration(
              RedissonClientConfiguration.class, CustomDistributedLockAspectConfiguration.class)
          .run(
              context -> {
                assertThat(context).hasSingleBean(DistributedLockAspect.class);
                assertThat(context.getBean(DistributedLockAspect.class))
                    .isSameAs(
                        context
                            .getBean(CustomDistributedLockAspectConfiguration.class)
                            .customAspect());
              });
    }
  }

  @Nested
  @DisplayName("Properties Configuration Tests")
  class PropertiesConfigurationTests {

    @Test
    @DisplayName("Should use default properties when not configured")
    void shouldUseDefaultPropertiesWhenNotConfigured() {
      contextRunner
          .withUserConfiguration(RedissonClientConfiguration.class)
          .run(
              context -> {
                LocksmithProperties properties = context.getBean(LocksmithProperties.class);
                assertThat(properties.leaseTime())
                    .isEqualTo(LocksmithProperties.DEFAULT_LEASE_TIME);
                assertThat(properties.waitTime()).isEqualTo(LocksmithProperties.DEFAULT_WAIT_TIME);
                assertThat(properties.keyPrefix())
                    .isEqualTo(LocksmithProperties.DEFAULT_KEY_PREFIX);
              });
    }

    @Test
    @DisplayName("Should apply custom lease time from properties")
    void shouldApplyCustomLeaseTimeFromProperties() {
      contextRunner
          .withUserConfiguration(RedissonClientConfiguration.class)
          .withPropertyValues("locksmith.lease-time=5m")
          .run(
              context -> {
                LocksmithProperties properties = context.getBean(LocksmithProperties.class);
                assertThat(properties.leaseTime().toMinutes()).isEqualTo(5);
              });
    }

    @Test
    @DisplayName("Should apply custom wait time from properties")
    void shouldApplyCustomWaitTimeFromProperties() {
      contextRunner
          .withUserConfiguration(RedissonClientConfiguration.class)
          .withPropertyValues("locksmith.wait-time=30s")
          .run(
              context -> {
                LocksmithProperties properties = context.getBean(LocksmithProperties.class);
                assertThat(properties.waitTime().toSeconds()).isEqualTo(30);
              });
    }

    @Test
    @DisplayName("Should apply custom key prefix from properties")
    void shouldApplyCustomKeyPrefixFromProperties() {
      contextRunner
          .withUserConfiguration(RedissonClientConfiguration.class)
          .withPropertyValues("locksmith.key-prefix=myapp:")
          .run(
              context -> {
                LocksmithProperties properties = context.getBean(LocksmithProperties.class);
                assertThat(properties.keyPrefix()).isEqualTo("myapp:");
              });
    }

    @Test
    @DisplayName("Should apply all custom properties")
    void shouldApplyAllCustomProperties() {
      contextRunner
          .withUserConfiguration(RedissonClientConfiguration.class)
          .withPropertyValues(
              "locksmith.lease-time=15m", "locksmith.wait-time=90s", "locksmith.key-prefix=custom:")
          .run(
              context -> {
                LocksmithProperties properties = context.getBean(LocksmithProperties.class);
                assertThat(properties.leaseTime().toMinutes()).isEqualTo(15);
                assertThat(properties.waitTime().toSeconds()).isEqualTo(90);
                assertThat(properties.keyPrefix()).isEqualTo("custom:");
              });
    }

    @Test
    @DisplayName("Should parse ISO-8601 duration format for lease time")
    void shouldParseIso8601DurationFormatForLeaseTime() {
      contextRunner
          .withUserConfiguration(RedissonClientConfiguration.class)
          .withPropertyValues("locksmith.lease-time=PT20M")
          .run(
              context -> {
                LocksmithProperties properties = context.getBean(LocksmithProperties.class);
                assertThat(properties.leaseTime().toMinutes()).isEqualTo(20);
              });
    }

    @Test
    @DisplayName("Should parse ISO-8601 duration format for wait time")
    void shouldParseIso8601DurationFormatForWaitTime() {
      contextRunner
          .withUserConfiguration(RedissonClientConfiguration.class)
          .withPropertyValues("locksmith.wait-time=PT2M30S")
          .run(
              context -> {
                LocksmithProperties properties = context.getBean(LocksmithProperties.class);
                assertThat(properties.waitTime().toSeconds()).isEqualTo(150);
              });
    }
  }

  @Nested
  @DisplayName("Context Failure Tests")
  class ContextFailureTests {

    @Test
    @DisplayName("Should fail context when invalid duration format is provided")
    void shouldFailContextWhenInvalidDurationFormatProvided() {
      contextRunner
          .withUserConfiguration(RedissonClientConfiguration.class)
          .withPropertyValues("locksmith.lease-time=invalid")
          .run(
              context -> {
                assertThat(context).hasFailed();
              });
    }
  }

  @Configuration
  static class RedissonClientConfiguration {

    @Bean
    RedissonClient redissonClient() {
      return mock(RedissonClient.class);
    }
  }

  @Configuration
  static class CustomDistributedLockAspectConfiguration {

    @Bean
    DistributedLockAspect customAspect() {
      return new DistributedLockAspect(mock(RedissonClient.class), LocksmithProperties.defaults());
    }
  }
}
