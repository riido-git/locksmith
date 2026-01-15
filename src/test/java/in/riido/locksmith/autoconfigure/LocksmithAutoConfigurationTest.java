package in.riido.locksmith.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import in.riido.locksmith.aspect.DistributedLockAspect;
import in.riido.locksmith.aspect.DistributedSemaphoreAspect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
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
    @DisplayName("Should create DistributedSemaphoreAspect when RedissonClient is present")
    void shouldCreateDistributedSemaphoreAspectWhenRedissonClientPresent() {
      contextRunner
          .withUserConfiguration(RedissonClientConfiguration.class)
          .run(
              context -> {
                assertThat(context).hasSingleBean(DistributedSemaphoreAspect.class);
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
            assertThat(context).doesNotHaveBean(DistributedSemaphoreAspect.class);
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
                            .customAspect(context));
              });
    }
  }

  @Nested
  @DisplayName("Lock Properties Configuration Tests")
  class LockPropertiesConfigurationTests {

    @Test
    @DisplayName("Should use default lock properties when not configured")
    void shouldUseDefaultLockPropertiesWhenNotConfigured() {
      contextRunner
          .withUserConfiguration(RedissonClientConfiguration.class)
          .run(
              context -> {
                LocksmithProperties properties = context.getBean(LocksmithProperties.class);
                assertThat(properties.lock().leaseTime())
                    .isEqualTo(LocksmithProperties.LockProperties.DEFAULT_LEASE_TIME);
                assertThat(properties.lock().waitTime())
                    .isEqualTo(LocksmithProperties.LockProperties.DEFAULT_WAIT_TIME);
                assertThat(properties.lock().keyPrefix())
                    .isEqualTo(LocksmithProperties.LockProperties.DEFAULT_KEY_PREFIX);
                assertThat(properties.lock().debug())
                    .isEqualTo(LocksmithProperties.LockProperties.DEFAULT_DEBUG);
              });
    }

    @Test
    @DisplayName("Should apply custom lock lease time from properties")
    void shouldApplyCustomLockLeaseTimeFromProperties() {
      contextRunner
          .withUserConfiguration(RedissonClientConfiguration.class)
          .withPropertyValues("locksmith.lock.lease-time=5m")
          .run(
              context -> {
                LocksmithProperties properties = context.getBean(LocksmithProperties.class);
                assertThat(properties.lock().leaseTime().toMinutes()).isEqualTo(5);
              });
    }

    @Test
    @DisplayName("Should apply custom lock wait time from properties")
    void shouldApplyCustomLockWaitTimeFromProperties() {
      contextRunner
          .withUserConfiguration(RedissonClientConfiguration.class)
          .withPropertyValues("locksmith.lock.wait-time=30s")
          .run(
              context -> {
                LocksmithProperties properties = context.getBean(LocksmithProperties.class);
                assertThat(properties.lock().waitTime().toSeconds()).isEqualTo(30);
              });
    }

    @Test
    @DisplayName("Should apply custom lock key prefix from properties")
    void shouldApplyCustomLockKeyPrefixFromProperties() {
      contextRunner
          .withUserConfiguration(RedissonClientConfiguration.class)
          .withPropertyValues("locksmith.lock.key-prefix=myapp:")
          .run(
              context -> {
                LocksmithProperties properties = context.getBean(LocksmithProperties.class);
                assertThat(properties.lock().keyPrefix()).isEqualTo("myapp:");
              });
    }

    @Test
    @DisplayName("Should apply all custom lock properties")
    void shouldApplyAllCustomLockProperties() {
      contextRunner
          .withUserConfiguration(RedissonClientConfiguration.class)
          .withPropertyValues(
              "locksmith.lock.lease-time=15m",
              "locksmith.lock.wait-time=90s",
              "locksmith.lock.key-prefix=custom:",
              "locksmith.lock.debug=true")
          .run(
              context -> {
                LocksmithProperties properties = context.getBean(LocksmithProperties.class);
                assertThat(properties.lock().leaseTime().toMinutes()).isEqualTo(15);
                assertThat(properties.lock().waitTime().toSeconds()).isEqualTo(90);
                assertThat(properties.lock().keyPrefix()).isEqualTo("custom:");
                assertThat(properties.lock().debug()).isTrue();
              });
    }

    @Test
    @DisplayName("Should parse ISO-8601 duration format for lock lease time")
    void shouldParseIso8601DurationFormatForLockLeaseTime() {
      contextRunner
          .withUserConfiguration(RedissonClientConfiguration.class)
          .withPropertyValues("locksmith.lock.lease-time=PT20M")
          .run(
              context -> {
                LocksmithProperties properties = context.getBean(LocksmithProperties.class);
                assertThat(properties.lock().leaseTime().toMinutes()).isEqualTo(20);
              });
    }

    @Test
    @DisplayName("Should parse ISO-8601 duration format for lock wait time")
    void shouldParseIso8601DurationFormatForLockWaitTime() {
      contextRunner
          .withUserConfiguration(RedissonClientConfiguration.class)
          .withPropertyValues("locksmith.lock.wait-time=PT2M30S")
          .run(
              context -> {
                LocksmithProperties properties = context.getBean(LocksmithProperties.class);
                assertThat(properties.lock().waitTime().toSeconds()).isEqualTo(150);
              });
    }
  }

  @Nested
  @DisplayName("Semaphore Properties Configuration Tests")
  class SemaphorePropertiesConfigurationTests {

    @Test
    @DisplayName("Should use default semaphore properties when not configured")
    void shouldUseDefaultSemaphorePropertiesWhenNotConfigured() {
      contextRunner
          .withUserConfiguration(RedissonClientConfiguration.class)
          .run(
              context -> {
                LocksmithProperties properties = context.getBean(LocksmithProperties.class);
                assertThat(properties.semaphore().leaseTime())
                    .isEqualTo(LocksmithProperties.SemaphoreProperties.DEFAULT_LEASE_TIME);
                assertThat(properties.semaphore().waitTime())
                    .isEqualTo(LocksmithProperties.SemaphoreProperties.DEFAULT_WAIT_TIME);
                assertThat(properties.semaphore().keyPrefix())
                    .isEqualTo(LocksmithProperties.SemaphoreProperties.DEFAULT_KEY_PREFIX);
                assertThat(properties.semaphore().debug())
                    .isEqualTo(LocksmithProperties.SemaphoreProperties.DEFAULT_DEBUG);
              });
    }

    @Test
    @DisplayName("Should apply custom semaphore lease time from properties")
    void shouldApplyCustomSemaphoreLeaseTimeFromProperties() {
      contextRunner
          .withUserConfiguration(RedissonClientConfiguration.class)
          .withPropertyValues("locksmith.semaphore.lease-time=3m")
          .run(
              context -> {
                LocksmithProperties properties = context.getBean(LocksmithProperties.class);
                assertThat(properties.semaphore().leaseTime().toMinutes()).isEqualTo(3);
              });
    }

    @Test
    @DisplayName("Should apply all custom semaphore properties")
    void shouldApplyAllCustomSemaphoreProperties() {
      contextRunner
          .withUserConfiguration(RedissonClientConfiguration.class)
          .withPropertyValues(
              "locksmith.semaphore.lease-time=8m",
              "locksmith.semaphore.wait-time=45s",
              "locksmith.semaphore.key-prefix=sem:",
              "locksmith.semaphore.debug=true")
          .run(
              context -> {
                LocksmithProperties properties = context.getBean(LocksmithProperties.class);
                assertThat(properties.semaphore().leaseTime().toMinutes()).isEqualTo(8);
                assertThat(properties.semaphore().waitTime().toSeconds()).isEqualTo(45);
                assertThat(properties.semaphore().keyPrefix()).isEqualTo("sem:");
                assertThat(properties.semaphore().debug()).isTrue();
              });
    }
  }

  @Nested
  @DisplayName("Context Failure Tests")
  class ContextFailureTests {

    @Test
    @DisplayName("Should fail context when invalid duration format is provided for lock")
    void shouldFailContextWhenInvalidDurationFormatProvidedForLock() {
      contextRunner
          .withUserConfiguration(RedissonClientConfiguration.class)
          .withPropertyValues("locksmith.lock.lease-time=invalid")
          .run(
              context -> {
                assertThat(context).hasFailed();
              });
    }

    @Test
    @DisplayName("Should fail context when invalid duration format is provided for semaphore")
    void shouldFailContextWhenInvalidDurationFormatProvidedForSemaphore() {
      contextRunner
          .withUserConfiguration(RedissonClientConfiguration.class)
          .withPropertyValues("locksmith.semaphore.lease-time=invalid")
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
    DistributedLockAspect customAspect(ApplicationContext applicationContext) {
      return new DistributedLockAspect(
          mock(RedissonClient.class), LocksmithProperties.defaults(), applicationContext);
    }
  }
}
