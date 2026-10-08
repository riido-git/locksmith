package in.riido.locksmith.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import in.riido.locksmith.DistributedLock;
import in.riido.locksmith.LocksmithConfigurationException;
import in.riido.locksmith.aop.LocksmithAdvisor;
import in.riido.locksmith.aop.LocksmithInterceptor;
import in.riido.locksmith.aop.MethodSpecFactory;
import in.riido.locksmith.lock.LockOperations;
import in.riido.locksmith.metrics.LocksmithMetrics;
import in.riido.locksmith.metrics.MicrometerLocksmithMetrics;
import in.riido.locksmith.metrics.NoOpLocksmithMetrics;
import in.riido.locksmith.semaphore.SemaphoreOperations;
import in.riido.locksmith.support.AnnotationValidator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.redisson.api.RedissonClient;
import org.slf4j.LoggerFactory;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@DisplayName("LocksmithAutoConfiguration")
class LocksmithAutoConfigurationTest {

  private static final List<Class<?>> LOCKSMITH_BEANS =
      List.of(
          LockOperations.class,
          SemaphoreOperations.class,
          LocksmithInterceptor.class,
          LocksmithAdvisor.class,
          MethodSpecFactory.class,
          AnnotationValidator.class);

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  AopAutoConfiguration.class,
                  LocksmithAutoConfiguration.class,
                  LocksmithDisabledAutoConfiguration.class,
                  LocksmithInactiveAutoConfiguration.class));

  private final ApplicationContextRunner withRedisson =
      runner.withBean(RedissonClient.class, () -> mock(RedissonClient.class));

  /** Spring's logger for beans created before every post-processor is registered. */
  private static final String POST_PROCESSOR_CHECKER =
      "org.springframework.context.support.PostProcessorRegistrationDelegate$BeanPostProcessorChecker";

  private final List<Logger> loggers =
      List.of(
          logger(LocksmithAutoConfiguration.class),
          logger(LocksmithDisabledAutoConfiguration.class),
          logger(LocksmithInactiveAutoConfiguration.class),
          (Logger) LoggerFactory.getLogger(POST_PROCESSOR_CHECKER));
  private ListAppender<ILoggingEvent> appender;

  private static Logger logger(Class<?> type) {
    return (Logger) LoggerFactory.getLogger(type);
  }

  @BeforeEach
  void captureLogs() {
    appender = new ListAppender<>();
    appender.start();
    loggers.forEach(logger -> logger.addAppender(appender));
  }

  @AfterEach
  void releaseLogs() {
    loggers.forEach(logger -> logger.detachAppender(appender));
  }

  private List<ILoggingEvent> events(Class<?> loggerType, Level level) {
    return appender.list.stream()
        .filter(e -> e.getLoggerName().equals(loggerType.getName()) && e.getLevel() == level)
        .toList();
  }

  static class LockedService {
    @DistributedLock(key = "k")
    public void run() {}
  }

  private List<ILoggingEvent> checkerEvents() {
    return appender.list.stream()
        .filter(e -> e.getLoggerName().equals(POST_PROCESSOR_CHECKER))
        .filter(e -> e.getLevel() == Level.WARN || e.getLevel() == Level.INFO)
        .toList();
  }

  private static void assertFailsNaming(AssertableApplicationContext context, String value) {
    assertThat(context)
        .getFailure()
        .isExactlyInstanceOf(LocksmithConfigurationException.class)
        .hasMessage("locksmith.enabled must be true or false, got [" + value + "]");
  }

  private static void assertNoLocksmithBeans(AssertableApplicationContext context) {
    assertThat(context).hasNotFailed();
    LOCKSMITH_BEANS.forEach(type -> assertThat(context).doesNotHaveBean(type));
    assertThat(context).doesNotHaveBean(LocksmithMetrics.class);
  }

  @Nested
  @DisplayName("with a RedissonClient bean")
  class WithRedisson {

    @Test
    @DisplayName("registers operations, interceptor, advisor, factory and validator")
    void registersBeans() {
      withRedisson.run(
          context -> LOCKSMITH_BEANS.forEach(type -> assertThat(context).hasSingleBean(type)));
    }

    @Test
    @DisplayName("logs the startup INFO line once, with prefix and Boot and Redisson versions")
    void logsStartupLineOnce() {
      withRedisson.run(
          context -> {
            assertThat(context).hasNotFailed();
            List<ILoggingEvent> info = events(LocksmithAutoConfiguration.class, Level.INFO);
            assertThat(info).hasSize(1);
            assertThat(info.get(0).getFormattedMessage())
                .startsWith("Locksmith enabled: key-prefix [locksmith:], semaphore lease-time PT5M")
                .contains("Spring Boot 4.")
                .contains("Redisson 4.");
          });
    }

    @Test
    @DisplayName("creates no bean before every post-processor is registered, logging once at INFO")
    void noEarlyBeans() {
      withRedisson
          .withBean(LockedService.class)
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                assertThat(checkerEvents()).isEmpty();
                assertThat(events(LocksmithAutoConfiguration.class, Level.INFO)).hasSize(1);
              });
    }

    @Test
    @DisplayName("creates no bean early with a MeterRegistry bean either")
    void noEarlyBeansWithMeterRegistry() {
      withRedisson
          .withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new)
          .withBean(LockedService.class)
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                assertThat(checkerEvents()).isEmpty();
                assertThat(events(LocksmithAutoConfiguration.class, Level.INFO)).hasSize(1);
              });
    }

    @Test
    @DisplayName("uses MicrometerLocksmithMetrics when a MeterRegistry bean exists")
    void micrometerMetrics() {
      withRedisson
          .withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new)
          .run(
              context ->
                  assertThat(context)
                      .getBean(LocksmithMetrics.class)
                      .isInstanceOf(MicrometerLocksmithMetrics.class));
    }

    @Test
    @DisplayName("uses NoOpLocksmithMetrics when no MeterRegistry bean exists")
    void noOpMetrics() {
      withRedisson.run(
          context ->
              assertThat(context)
                  .getBean(LocksmithMetrics.class)
                  .isInstanceOf(NoOpLocksmithMetrics.class));
    }
  }

  @Test
  @DisplayName("registers nothing and logs the inactive WARN without a RedissonClient bean")
  void nothingWithoutRedisson() {
    runner.run(
        context -> {
          assertNoLocksmithBeans(context);
          List<ILoggingEvent> warnings =
              events(LocksmithInactiveAutoConfiguration.class, Level.WARN);
          assertThat(warnings).hasSize(1);
          assertThat(warnings.get(0).getFormattedMessage())
              .isEqualTo(
                  "Locksmith is inactive: there is no RedissonClient bean, so annotated methods"
                      + " run without any coordination");
        });
  }

  @ParameterizedTest(name = "locksmith.enabled={0}")
  @ValueSource(strings = {"false", "FALSE"})
  @DisplayName(
      "registers nothing and logs the disabled WARN with locksmith.enabled false, any case")
  void disabled(String value) {
    withRedisson
        .withPropertyValues("locksmith.enabled=" + value)
        .run(
            context -> {
              assertNoLocksmithBeans(context);
              List<ILoggingEvent> warnings =
                  events(LocksmithDisabledAutoConfiguration.class, Level.WARN);
              assertThat(warnings).hasSize(1);
              assertThat(warnings.get(0).getFormattedMessage())
                  .isEqualTo(
                      "Locksmith is disabled: annotated methods run without any coordination");
              assertThat(events(LocksmithInactiveAutoConfiguration.class, Level.WARN)).isEmpty();
            });
  }

  /** What SpringApplication adds when spring.main.lazy-initialization is true. */
  private static ApplicationContextRunner lazy(ApplicationContextRunner runner) {
    return runner.withInitializer(
        context ->
            context.addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor()));
  }

  @Test
  @DisplayName("logs the inactive WARN under lazy initialization too")
  void inactiveWarningWhenLazy() {
    lazy(runner)
        .run(
            context ->
                assertThat(events(LocksmithInactiveAutoConfiguration.class, Level.WARN))
                    .hasSize(1));
  }

  @Test
  @DisplayName("logs the disabled WARN under lazy initialization too")
  void disabledWarningWhenLazy() {
    lazy(withRedisson)
        .withPropertyValues("locksmith.enabled=false")
        .run(
            context ->
                assertThat(events(LocksmithDisabledAutoConfiguration.class, Level.WARN))
                    .hasSize(1));
  }

  @ParameterizedTest(name = "locksmith.enabled={0}")
  @ValueSource(strings = {"true", "TRUE"})
  @DisplayName("registers Locksmith with locksmith.enabled true, any case, as when it is not set")
  void enabledInAnyCase(String value) {
    withRedisson
        .withPropertyValues("locksmith.enabled=" + value)
        .run(
            context -> {
              LOCKSMITH_BEANS.forEach(type -> assertThat(context).hasSingleBean(type));
              assertThat(events(LocksmithDisabledAutoConfiguration.class, Level.WARN)).isEmpty();
              assertThat(events(LocksmithInactiveAutoConfiguration.class, Level.WARN)).isEmpty();
            });
  }

  @ParameterizedTest(name = "locksmith.enabled=[{0}]")
  @ValueSource(strings = {"treu", "yes", "1", ""})
  @DisplayName("fails the startup on a locksmith.enabled value other than true or false, naming it")
  void invalidEnabledValue(String value) {
    // Before the fix such a value matched none of the three configurations: no bean, no proxy, no
    // WARN, and annotated methods ran without coordination.
    withRedisson
        .withPropertyValues("locksmith.enabled=" + value)
        .run(context -> assertFailsNaming(context, value));
  }

  @Test
  @DisplayName(
      "fails the startup on an invalid locksmith.enabled without a RedissonClient bean too")
  void invalidEnabledValueWithoutRedisson() {
    runner
        .withPropertyValues("locksmith.enabled=treu")
        .run(context -> assertFailsNaming(context, "treu"));
  }

  @Test
  @DisplayName("logs no disabled or inactive WARN when enabled with a RedissonClient bean")
  void noWarningWhenEnabled() {
    withRedisson.run(
        context -> {
          assertThat(events(LocksmithDisabledAutoConfiguration.class, Level.WARN)).isEmpty();
          assertThat(events(LocksmithInactiveAutoConfiguration.class, Level.WARN)).isEmpty();
        });
  }
}
