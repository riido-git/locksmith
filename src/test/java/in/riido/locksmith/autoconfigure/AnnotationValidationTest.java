package in.riido.locksmith.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import in.riido.locksmith.DistributedLock;
import in.riido.locksmith.DistributedSemaphore;
import in.riido.locksmith.LockType;
import in.riido.locksmith.LocksmithConfigurationException;
import in.riido.locksmith.OnFailure;
import in.riido.locksmith.autoconfigure.otherpackage.InheritedMethods;
import in.riido.locksmith.autoconfigure.otherpackage.SamePackageChild;
import in.riido.locksmith.lock.LockFailureContext;
import in.riido.locksmith.lock.LockFailureHandler;
import in.riido.locksmith.semaphore.SemaphoreFailureContext;
import in.riido.locksmith.semaphore.SemaphoreFailureHandler;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;
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

  static class PlainCompletableFuture {
    @DistributedLock(key = "k")
    public CompletableFuture<String> run() {
      return null;
    }
  }

  static class FinalMethod {
    @DistributedLock(key = "k")
    public final void run() {}
  }

  @SuppressWarnings("unused")
  static class PrivateMethod {
    @DistributedSemaphore(key = "k", permits = "1")
    private void run() {}
  }

  static class PackagePrivateLockFromOtherPackage extends InheritedMethods.PackagePrivateLock {}

  static class PackagePrivateSemaphoreFromOtherPackage
      extends InheritedMethods.PackagePrivateSemaphore {}

  static class ProtectedLockFromOtherPackage extends InheritedMethods.ProtectedLock {}

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

  static class ReentrantOrder {
    @DistributedLock(key = "order:#{#id}")
    public void run(String id) {}
  }

  static class ReadOrder {
    @DistributedLock(key = "order:#{#id}", type = LockType.READ)
    public void run(String id) {}
  }

  static class WriteOrder {
    @DistributedLock(key = "order:#{#id}", type = LockType.WRITE)
    public void run(String id) {}
  }

  static class TwoReports {
    @DistributedSemaphore(key = "reports", permits = "2")
    public void run() {}
  }

  static class FiveReports {
    @DistributedSemaphore(key = "reports", permits = "5")
    public void run() {}
  }

  static class OtherTwoReports {
    @DistributedSemaphore(key = "reports", permits = "2")
    public void run() {}
  }

  static class PlaceholderReports {
    @DistributedSemaphore(key = "reports", permits = "${reports.max}")
    public void run() {}
  }

  /** The quotes are part of this key; {@link IslandReports} resolves to plain reports. */
  static class QuotedReports {
    @DistributedSemaphore(key = "'reports'", permits = "1")
    public void run() {}
  }

  static class IslandReports {
    @DistributedSemaphore(key = "#{'reports'}", permits = "2")
    public void run() {}
  }

  static class QuotedReportsLock {
    @DistributedLock(key = "'reports'")
    public void run() {}
  }

  static class IslandReportsLock {
    @DistributedLock(key = "#{'reports'}")
    public void run() {}
  }

  static class IslandReportsWrite {
    @DistributedLock(key = "#{'reports'}", type = LockType.WRITE)
    public void run() {}
  }

  interface BlankKeyApi {
    @DistributedLock(key = "")
    void run();
  }

  static class InheritsBlankKey implements BlankKeyApi {
    @Override
    public void run() {}
  }

  /** The kind of interface a Feign or HTTP interface client implements. */
  interface RemoteApi {
    @DistributedLock(key = "remote:#{#id}")
    String fetch(String id);

    @DistributedSemaphore(key = "remote", permits = "5")
    String search(String query);
  }

  static class RemoteApiImpl implements RemoteApi {
    @Override
    public String fetch(String id) {
      return id;
    }

    @Override
    public String search(String query) {
      return query;
    }
  }

  interface RemoteApiUnknownVariable {
    @DistributedLock(key = "remote:#{#nope}")
    String fetch(String id);
  }

  /** A bean that is itself a JDK dynamic proxy, as such a client is. */
  private static <T> T jdkProxy(Class<T> type, T implementation) {
    return type.cast(
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (proxy, method, args) -> method.invoke(implementation, args)));
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

  /**
   * Defines the class again from its bytes in a class loader of its own, so the class and its
   * superclass share a package but not a class loader.
   */
  private static Class<?> defineInOwnClassLoader(Class<?> type) throws IOException {
    byte[] bytes;
    try (InputStream in =
        type.getClassLoader().getResourceAsStream(type.getName().replace('.', '/') + ".class")) {
      bytes = in.readAllBytes();
    }
    return new ClassLoader(type.getClassLoader()) {
      Class<?> define() {
        return defineClass(type.getName(), bytes, 0, bytes.length);
      }
    }.define();
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
    @DisplayName(
        "on an unknown variable in a JDK proxy bean's interface method, naming that method")
    void unknownVariableOnJdkProxyBean() {
      runner
          .withBean(
              RemoteApiUnknownVariable.class,
              () -> jdkProxy(RemoteApiUnknownVariable.class, id -> id))
          .run(
              context ->
                  assertThat(failure(context))
                      .hasMessageContaining(RemoteApiUnknownVariable.class.getName() + ".fetch")
                      .hasMessageContaining("#nope"));
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
              + ".run: return type java.util.concurrent.Future would let the work outlive the lock,"
              + " which is released when the method returns; mark the method @Async and declare"
              + " Future or CompletableFuture, so the lock covers its body on the worker thread");
    }

    @Test
    @DisplayName("on a CompletableFuture return type without @Async")
    void completableFutureWithoutAsync() {
      assertFails(
          PlainCompletableFuture.class,
          "return type java.util.concurrent.CompletableFuture would let the work outlive the lock",
          "mark the method @Async");
    }

    @Test
    @DisplayName("on a final method, which Spring's proxy never intercepts")
    void finalMethod() {
      assertFails(
          FinalMethod.class,
          "@DistributedLock on ",
          ": the method is final, so Spring's proxy never intercepts it");
    }

    @Test
    @DisplayName("on a private method, which Spring's proxy never intercepts")
    void privateMethod() {
      assertFails(
          PrivateMethod.class,
          "@DistributedSemaphore on ",
          ": the method is private, so Spring's proxy never intercepts it");
    }

    @Test
    @DisplayName(
        "on a package-private method inherited from another package, which Spring's proxy never"
            + " intercepts")
    void packagePrivateFromAnotherPackage() {
      runner
          .withBean(PackagePrivateLockFromOtherPackage.class)
          .run(
              context ->
                  assertThat(failure(context))
                      .hasMessage(
                          "@DistributedLock on "
                              + InheritedMethods.PackagePrivateLock.class.getName()
                              + ".run: the method is package-private but bean class "
                              + PackagePrivateLockFromOtherPackage.class.getName()
                              + " is in another package or class loader, so Spring's proxy never"
                              + " intercepts it and it would run without coordination; make it"
                              + " public or protected"));
    }

    @Test
    @DisplayName("on a package-private semaphore method inherited from another package")
    void packagePrivateSemaphoreFromAnotherPackage() {
      runner
          .withBean(PackagePrivateSemaphoreFromOtherPackage.class)
          .run(
              context ->
                  assertThat(failure(context))
                      .hasMessageStartingWith(
                          "@DistributedSemaphore on "
                              + InheritedMethods.PackagePrivateSemaphore.class.getName()
                              + ".run: the method is package-private but bean class "
                              + PackagePrivateSemaphoreFromOtherPackage.class.getName()));
    }

    @Test
    @DisplayName(
        "on a package-private method inherited in its package by a class of another class loader")
    void packagePrivateFromAnotherClassLoader() throws IOException {
      Class<?> child = defineInOwnClassLoader(SamePackageChild.class);

      runner
          .withBean(child)
          .run(
              context ->
                  assertThat(failure(context))
                      .hasMessageContaining(
                          ".run: the method is package-private but bean class "
                              + SamePackageChild.class.getName()
                              + " is in another package or class loader"));
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
    @DisplayName("on one key text used with REENTRANT and READ, naming both methods and types")
    void reentrantAndReadOnOneKey() {
      runner
          .withBean(ReentrantOrder.class)
          .withBean(ReadOrder.class)
          .run(
              context ->
                  assertThat(failure(context))
                      .hasMessageContainingAll(
                          ReentrantOrder.class.getName() + ".run",
                          ReadOrder.class.getName() + ".run",
                          "key [order:#{#id}]",
                          "REENTRANT",
                          "READ",
                          "Pick one kind for that key: REENTRANT, or READ/WRITE."));
    }

    @Test
    @DisplayName(
        "on one semaphore key text used with two permit counts, naming both methods and counts")
    void oneSemaphoreKeyWithTwoCounts() {
      runner
          .withBean(TwoReports.class)
          .withBean(FiveReports.class)
          .run(
              context ->
                  assertThat(failure(context))
                      .hasMessageContainingAll(
                          "@DistributedSemaphore key [reports]",
                          "permits 2 on " + TwoReports.class.getName() + ".run",
                          "permits 5 on " + FiveReports.class.getName() + ".run",
                          "Use one count for that key, or give each count its own key."));
    }

    @Test
    @DisplayName("on one #{...}-only key text used with REENTRANT and WRITE, quoting it as written")
    void islandOnlyKeyKeepsItsBraces() {
      runner
          .withBean(IslandReportsLock.class)
          .withBean(IslandReportsWrite.class)
          .run(
              context ->
                  assertThat(failure(context))
                      .hasMessageContaining("@DistributedLock key [#{'reports'}] is used with"));
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
  @DisplayName("starts with READ and WRITE on one key text")
  void startsWithReadAndWriteOnOneKey() {
    runner
        .withBean(ReadOrder.class)
        .withBean(WriteOrder.class)
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  @DisplayName("starts with one semaphore key text and the same permit count on two methods")
  void startsWithOneSemaphoreKeyAndOneCount() {
    runner
        .withBean(TwoReports.class)
        .withBean(OtherTwoReports.class)
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  @DisplayName("starts when a placeholder resolves to the count another method states")
  void startsWithPlaceholderResolvingToTheSameCount() {
    runner
        .withPropertyValues("reports.max=2")
        .withBean(TwoReports.class)
        .withBean(PlaceholderReports.class)
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  @DisplayName(
      "starts with semaphore keys 'reports' and #{'reports'} and two counts: they are different"
          + " keys")
  void startsWithQuotedAndIslandSemaphoreKeys() {
    runner
        .withBean(QuotedReports.class)
        .withBean(IslandReports.class)
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  @DisplayName(
      "starts with lock keys 'reports' as REENTRANT and #{'reports'} as WRITE: they are different"
          + " keys")
  void startsWithQuotedAndIslandLockKeys() {
    runner
        .withBean(QuotedReportsLock.class)
        .withBean(IslandReportsWrite.class)
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  @DisplayName(
      "starts with a package-private method inherited in its package, and a protected one"
          + " inherited from another package")
  void startsWithInheritedMethodsTheProxyCanOverride() {
    runner
        .withBean(SamePackageChild.class)
        .withBean(ProtectedLockFromOtherPackage.class)
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  @DisplayName("starts with a plain Future return type when the class is @Async")
  void startsWithAsyncClassFuture() {
    runner.withBean(AsyncClassFuture.class).run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  @DisplayName("starts with a bean that is itself a JDK proxy, such as a Feign client")
  void startsWithJdkProxyBean() {
    runner
        .withBean(RemoteApi.class, () -> jdkProxy(RemoteApi.class, new RemoteApiImpl()))
        .run(context -> assertThat(context).hasNotFailed());
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
