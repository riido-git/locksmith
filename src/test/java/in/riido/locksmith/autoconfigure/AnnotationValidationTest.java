package in.riido.locksmith.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import in.riido.locksmith.DistributedLock;
import in.riido.locksmith.DistributedSemaphore;
import in.riido.locksmith.LocksmithConfigurationException;
import in.riido.locksmith.OnFailure;
import in.riido.locksmith.lock.LockFailureContext;
import in.riido.locksmith.lock.LockFailureHandler;
import in.riido.locksmith.semaphore.SemaphoreFailureContext;
import in.riido.locksmith.semaphore.SemaphoreFailureHandler;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.Async;
import reactor.core.publisher.Mono;

/** Startup validation: a misconfigured annotation fails the context refresh. */
@DisplayName("startup validation")
class AnnotationValidationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(AopAutoConfiguration.class, LocksmithAutoConfiguration.class))
          .withBean(RedissonClient.class, () -> mock(RedissonClient.class));

  static class MarkerHandler implements LockFailureHandler {
    @Override
    public Object onFailure(LockFailureContext context) {
      return "marker";
    }
  }

  static class BlankKey {
    @DistributedLock(key = " ")
    public void run() {}
  }

  static class UnparsableKey {
    @DistributedLock(key = "a:#{#id +}")
    public void run(String id) {}
  }

  static class UnknownVariable {
    @DistributedLock(key = "a:#{#nope}")
    public void run(String id) {}
  }

  static class BadWaitTime {
    @DistributedLock(key = "k", waitTime = "soon")
    public void run() {}
  }

  static class NegativeWaitTime {
    @DistributedLock(key = "k", waitTime = "-5s")
    public void run() {}
  }

  static class BadLeaseTime {
    @DistributedLock(key = "k", leaseTime = "later")
    public void run() {}
  }

  static class NegativeLeaseTime {
    @DistributedLock(key = "k", leaseTime = "-1s")
    public void run() {}
  }

  static class ZeroLeaseTime {
    @DistributedLock(key = "k", leaseTime = "0s")
    public void run() {}
  }

  static class HandlerNotSet {
    @DistributedLock(key = "k", onFailure = OnFailure.HANDLER)
    public void run() {}
  }

  static class HandlerWithThrow {
    @DistributedLock(key = "k", handler = MarkerHandler.class)
    public void run() {}
  }

  static class HandlerPolicy {
    @DistributedLock(key = "k", onFailure = OnFailure.HANDLER, handler = MarkerHandler.class)
    public void run() {}
  }

  static class MarkerSemaphoreHandler implements SemaphoreFailureHandler {
    @Override
    public Object onFailure(SemaphoreFailureContext context) {
      return "marker";
    }
  }

  static class PermitsZero {
    @DistributedSemaphore(key = "k", permits = "0")
    public void run() {}
  }

  static class PermitsNotNumber {
    @DistributedSemaphore(key = "k", permits = "many")
    public void run() {}
  }

  static class PermitsUnresolvable {
    @DistributedSemaphore(key = "k", permits = "${reports.missing}")
    public void run() {}
  }

  static class PermitsPlaceholder {
    @DistributedSemaphore(key = "k", permits = "${reports.max}")
    public void run() {}
  }

  static class SemaphoreHandlerPolicy {
    @DistributedSemaphore(
        key = "k",
        permits = "2",
        onFailure = OnFailure.HANDLER,
        handler = MarkerSemaphoreHandler.class)
    public void run() {}
  }

  static class PlainFuture {
    @DistributedLock(key = "k")
    public Future<String> run() {
      return null;
    }
  }

  @Async
  static class AsyncClassFuture {
    @DistributedLock(key = "k")
    public Future<String> run() {
      return null;
    }
  }

  static class ReactiveReturn {
    @DistributedLock(key = "k")
    public Mono<String> run() {
      return null;
    }
  }

  interface BlankKeyApi {
    @DistributedLock(key = "")
    void run();
  }

  static class InheritsBlankKey implements BlankKeyApi {
    @Override
    public void run() {}
  }

  private static LocksmithConfigurationException failure(AssertableApplicationContext context) {
    assertThat(context).hasFailed();
    Throwable cause = context.getStartupFailure();
    while (cause != null && !(cause instanceof LocksmithConfigurationException)) {
      cause = cause.getCause();
    }
    assertThat(cause).as("LocksmithConfigurationException in the cause chain").isNotNull();
    return (LocksmithConfigurationException) cause;
  }

  private void assertFails(Class<?> beanClass, String... fragments) {
    runner
        .withBean(beanClass)
        .run(
            context ->
                assertThat(failure(context))
                    .hasMessageContaining(beanClass.getName() + ".run")
                    .hasMessageContainingAll(fragments));
  }

  @Nested
  @DisplayName("fails the refresh")
  class Fails {

    @Test
    @DisplayName("on a blank key, naming key")
    void blankKey() {
      assertFails(BlankKey.class, "key must not be blank");
    }

    @Test
    @DisplayName("on an unparsable key, with the SpEL error")
    void unparsableKey() {
      assertFails(UnparsableKey.class, "key [a:#{#id +}] is not a valid template");
    }

    @Test
    @DisplayName("on an unknown variable, with the variable and the -parameters hint")
    void unknownVariable() {
      assertFails(UnknownVariable.class, "#nope", "compile with -parameters or use #p0");
    }

    @Test
    @DisplayName("on an unparsable waitTime, with attribute and value")
    void badWaitTime() {
      assertFails(BadWaitTime.class, "waitTime", "[soon]");
    }

    @Test
    @DisplayName("on a negative waitTime, with attribute and value")
    void negativeWaitTime() {
      assertFails(NegativeWaitTime.class, "waitTime", "[-5s]");
    }

    @Test
    @DisplayName("on an unparsable leaseTime, with attribute and value")
    void badLeaseTime() {
      assertFails(BadLeaseTime.class, "leaseTime", "[later]");
    }

    @Test
    @DisplayName("on a negative leaseTime, with attribute and value")
    void negativeLeaseTime() {
      assertFails(NegativeLeaseTime.class, "leaseTime", "[-1s]");
    }

    @Test
    @DisplayName("on a zero leaseTime, with attribute and value")
    void zeroLeaseTime() {
      assertFails(ZeroLeaseTime.class, "leaseTime", "[0s]", "must be positive");
    }

    @Test
    @DisplayName("on HANDLER without handler")
    void handlerNotSet() {
      assertFails(HandlerNotSet.class, "HANDLER", LockFailureHandler.class.getName());
    }

    @Test
    @DisplayName("on handler set while onFailure is THROW")
    void handlerWithThrow() {
      assertFails(HandlerWithThrow.class, MarkerHandler.class.getName(), "onFailure is THROW");
    }

    @Test
    @DisplayName("on HANDLER with zero beans of the handler type, with type and count")
    void noHandlerBean() {
      assertFails(HandlerPolicy.class, MarkerHandler.class.getName(), "found 0");
    }

    @Test
    @DisplayName("on HANDLER with two beans of the handler type, with type and count")
    void twoHandlerBeans() {
      runner
          .withBean("first", MarkerHandler.class, MarkerHandler::new)
          .withBean("second", MarkerHandler.class, MarkerHandler::new)
          .withBean(HandlerPolicy.class)
          .run(
              context ->
                  assertThat(failure(context))
                      .hasMessageContaining(HandlerPolicy.class.getName() + ".run")
                      .hasMessageContaining(MarkerHandler.class.getName())
                      .hasMessageContaining("found 2"));
    }

    @Test
    @DisplayName("on a plain Future return type without @Async")
    void plainFuture() {
      assertFails(
          PlainFuture.class,
          "@DistributedLock on "
              + PlainFuture.class.getName()
              + ".run: return type java.util.concurrent.Future cannot be tracked to completion;"
              + " return CompletableFuture, or mark the method @Async");
    }

    @Test
    @DisplayName("on a reactive return type")
    void reactiveReturn() {
      assertFails(ReactiveReturn.class, "reactive return types are not supported");
    }

    @Test
    @DisplayName("on an annotation inherited from an interface")
    void inheritedFromInterface() {
      assertFails(InheritsBlankKey.class, "key must not be blank");
    }

    @Test
    @DisplayName("on semaphore permits 0, with the value")
    void permitsZero() {
      assertFails(PermitsZero.class, "@DistributedSemaphore", "permits [0]", "greater than zero");
    }

    @Test
    @DisplayName("on semaphore permits that are not a number, with the value")
    void permitsNotNumber() {
      assertFails(PermitsNotNumber.class, "@DistributedSemaphore", "permits [many]");
    }

    @Test
    @DisplayName("on an unresolvable semaphore permits placeholder, with the placeholder")
    void permitsUnresolvable() {
      assertFails(
          PermitsUnresolvable.class,
          "@DistributedSemaphore",
          "permits [${reports.missing}] cannot be resolved");
    }

    @Test
    @DisplayName("on semaphore HANDLER with zero beans of the handler type, with type and count")
    void noSemaphoreHandlerBean() {
      assertFails(
          SemaphoreHandlerPolicy.class,
          "@DistributedSemaphore",
          MarkerSemaphoreHandler.class.getName(),
          "found 0");
    }

    @Test
    @DisplayName("on semaphore HANDLER with two beans of the handler type, with type and count")
    void twoSemaphoreHandlerBeans() {
      runner
          .withBean("first", MarkerSemaphoreHandler.class, MarkerSemaphoreHandler::new)
          .withBean("second", MarkerSemaphoreHandler.class, MarkerSemaphoreHandler::new)
          .withBean(SemaphoreHandlerPolicy.class)
          .run(
              context ->
                  assertThat(failure(context))
                      .hasMessageContaining(SemaphoreHandlerPolicy.class.getName() + ".run")
                      .hasMessageContaining("@DistributedSemaphore")
                      .hasMessageContaining(MarkerSemaphoreHandler.class.getName())
                      .hasMessageContaining("found 2"));
    }
  }

  @Test
  @DisplayName("starts with semaphore permits from a resolvable placeholder")
  void startsWithPermitsPlaceholder() {
    runner
        .withPropertyValues("reports.max=4")
        .withBean(PermitsPlaceholder.class)
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  @DisplayName("starts with semaphore HANDLER and exactly one bean of the handler type")
  void startsWithOneSemaphoreHandlerBean() {
    runner
        .withBean(MarkerSemaphoreHandler.class)
        .withBean(SemaphoreHandlerPolicy.class)
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  @DisplayName("starts with a plain Future return type when the class is @Async")
  void startsWithAsyncClassFuture() {
    runner.withBean(AsyncClassFuture.class).run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  @DisplayName("starts with HANDLER and exactly one bean of the handler type")
  void startsWithOneHandlerBean() {
    runner
        .withBean(MarkerHandler.class)
        .withBean(HandlerPolicy.class)
        .run(context -> assertThat(context).hasNotFailed());
  }
}
