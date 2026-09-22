package in.riido.locksmith.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import in.riido.locksmith.DistributedLock;
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
import org.redisson.api.RedissonClient;
import org.slf4j.LoggerFactory;
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
                  LocksmithDisabledAutoConfiguration.class));

  private final ApplicationContextRunner withRedisson =
      runner.withBean(RedissonClient.class, () -> mock(RedissonClient.class));

  /** Spring's logger for beans created before every post-processor is registered. */
  private static final String POST_PROCESSOR_CHECKER =
      "org.springframework.context.support.PostProcessorRegistrationDelegate$BeanPostProcessorChecker";

  private final List<Logger> loggers =
      List.of(
          logger(LocksmithAutoConfiguration.class),
          logger(LocksmithDisabledAutoConfiguration.class),
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
  @DisplayName("registers nothing without a RedissonClient bean")
  void nothingWithoutRedisson() {
    runner.run(LocksmithAutoConfigurationTest::assertNoLocksmithBeans);
  }

  @Test
  @DisplayName("registers nothing and logs the disabled WARN with locksmith.enabled=false")
  void disabled() {
    withRedisson
        .withPropertyValues("locksmith.enabled=false")
        .run(
            context -> {
              assertNoLocksmithBeans(context);
              List<ILoggingEvent> warnings =
                  events(LocksmithDisabledAutoConfiguration.class, Level.WARN);
              assertThat(warnings).hasSize(1);
              assertThat(warnings.get(0).getFormattedMessage())
                  .isEqualTo(
                      "Locksmith is disabled: annotated methods run without any coordination");
            });
  }

  @Test
  @DisplayName("logs no disabled WARN when enabled")
  void noWarningWhenEnabled() {
    withRedisson.run(
        context ->
            assertThat(events(LocksmithDisabledAutoConfiguration.class, Level.WARN)).isEmpty());
  }
}
