package in.riido.locksmith.aop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import in.riido.locksmith.DistributedLock;
import in.riido.locksmith.DistributedSemaphore;
import in.riido.locksmith.LockType;
import in.riido.locksmith.LocksmithConfigurationException;
import in.riido.locksmith.OnFailure;
import in.riido.locksmith.aop.MethodSpec.LockSpec;
import in.riido.locksmith.aop.MethodSpec.SemaphoreSpec;
import in.riido.locksmith.autoconfigure.LocksmithProperties;
import in.riido.locksmith.lock.LockFailureContext;
import in.riido.locksmith.lock.LockFailureHandler;
import in.riido.locksmith.semaphore.SemaphoreFailureContext;
import in.riido.locksmith.semaphore.SemaphoreFailureHandler;
import in.riido.locksmith.support.KeyTemplate;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.Future;
import kotlin.coroutines.Continuation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.springframework.expression.ParseException;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.scheduling.annotation.Async;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@DisplayName("MethodSpecFactory")
class MethodSpecFactoryTest {

  private static final String FIXTURE = Fixture.class.getName();

  private static final Duration DEFAULT_SEMAPHORE_LEASE = Duration.ofSeconds(90);

  private final MethodSpecFactory factory =
      new MethodSpecFactory(
          new MockEnvironment().withProperty("reports.max", "4").withProperty("reports.bad", "x"),
          new LocksmithProperties(
              null, null, new LocksmithProperties.Semaphore(DEFAULT_SEMAPHORE_LEASE)));

  static class MarkerHandler implements LockFailureHandler {
    @Override
    public Object onFailure(LockFailureContext context) {
      return "marker";
    }
  }

  static class MarkerSemaphoreHandler implements SemaphoreFailureHandler {
    @Override
    public Object onFailure(SemaphoreFailureContext context) {
      return "marker";
    }
  }

  interface Annotated {
    @DistributedLock(key = "iface")
    void fromInterface();
  }

  @SuppressWarnings("unused")
  static class Fixture implements Annotated {
    @DistributedLock(key = "user:#{#userId}")
    void defaults(String userId) {}

    @DistributedLock(key = "k", type = LockType.WRITE)
    void write() {}

    @DistributedLock(key = "k", waitTime = "5s")
    void waitSimple() {}

    @DistributedLock(key = "k", waitTime = "PT5S")
    void waitIso() {}

    @DistributedLock(key = "k", leaseTime = "30s")
    void lease() {}

    @DistributedLock(key = "k", onFailure = OnFailure.SKIP)
    void skip() {}

    @DistributedLock(key = "k", onFailure = OnFailure.HANDLER, handler = MarkerHandler.class)
    void handler() {}

    @Override
    public void fromInterface() {}

    void unannotated() {}

    @DistributedLock(key = "  ")
    void blankKey() {}

    @DistributedLock(key = "a:#{#id +}")
    void unparsableKey(String id) {}

    @DistributedLock(key = "a:#{#nope}")
    void unknownVariable(String id) {}

    @DistributedLock(key = "k", waitTime = "soon")
    void badWait() {}

    @DistributedLock(key = "k", waitTime = "-5s")
    void negativeWait() {}

    @DistributedLock(key = "k", leaseTime = "later")
    void badLease() {}

    @DistributedLock(key = "k", leaseTime = "-1s")
    void negativeLease() {}

    @DistributedLock(key = "k", leaseTime = "0s")
    void zeroLease() {}

    @DistributedLock(key = "k", onFailure = OnFailure.HANDLER)
    void handlerMissing() {}

    @DistributedLock(key = "k", handler = MarkerHandler.class)
    void handlerWithThrow() {}

    @DistributedLock(key = "k", onFailure = OnFailure.SKIP, handler = MarkerHandler.class)
    void handlerWithSkip() {}

    @DistributedSemaphore(key = "report:#{#id}", permits = "2")
    void semaphore(String id) {}

    @DistributedSemaphore(key = "k", permits = "${reports.max}")
    void semaphorePlaceholder() {}

    @DistributedSemaphore(key = "k", permits = "2", waitTime = "3s", leaseTime = "30s")
    void semaphoreTimed() {}

    @DistributedSemaphore(
        key = "k",
        permits = "2",
        onFailure = OnFailure.HANDLER,
        handler = MarkerSemaphoreHandler.class)
    void semaphoreHandler() {}

    @DistributedSemaphore(key = "s", permits = "3")
    @DistributedLock(key = "l", type = LockType.WRITE)
    void both() {}

    @DistributedSemaphore(key = " ", permits = "2")
    void semaphoreBlankKey() {}

    @DistributedSemaphore(key = "a:#{#id +}", permits = "2")
    void semaphoreUnparsableKey(String id) {}

    @DistributedSemaphore(key = "a:#{#nope}", permits = "2")
    void semaphoreUnknownVariable(String id) {}

    @DistributedSemaphore(key = "k", permits = "many")
    void permitsNotNumber() {}

    @DistributedSemaphore(key = "k", permits = "${reports.bad}")
    void permitsPlaceholderNotNumber() {}

    @DistributedSemaphore(key = "k", permits = "0")
    void permitsZero() {}

    @DistributedSemaphore(key = "k", permits = "-2")
    void permitsNegative() {}

    @DistributedSemaphore(key = "k", permits = "${reports.missing}")
    void permitsUnresolvable() {}

    @DistributedSemaphore(key = "k", permits = "2", waitTime = "soon")
    void semaphoreBadWait() {}

    @DistributedSemaphore(key = "k", permits = "2", waitTime = "-5s")
    void semaphoreNegativeWait() {}

    @DistributedSemaphore(key = "k", permits = "2", leaseTime = "later")
    void semaphoreBadLease() {}

    @DistributedSemaphore(key = "k", permits = "2", leaseTime = "0s")
    void semaphoreZeroLease() {}

    @DistributedSemaphore(key = "k", permits = "2", leaseTime = "-1s")
    void semaphoreNegativeLease() {}

    @DistributedSemaphore(key = "k", permits = "2", onFailure = OnFailure.HANDLER)
    void semaphoreHandlerMissing() {}

    @DistributedSemaphore(key = "k", permits = "2", handler = MarkerSemaphoreHandler.class)
    void semaphoreHandlerWithThrow() {}
  }

  private static Method method(String name) {
    return Arrays.stream(Fixture.class.getDeclaredMethods())
        .filter(m -> m.getName().equals(name))
        .findFirst()
        .orElseThrow();
  }

  private LockSpec lock(String name) {
    MethodSpec spec = factory.create(method(name));
    assertThat(spec.semaphore()).isNull();
    assertThat(spec.lock()).isNotNull();
    return spec.lock();
  }

  private SemaphoreSpec semaphore(String name) {
    MethodSpec spec = factory.create(method(name));
    assertThat(spec.lock()).isNull();
    assertThat(spec.semaphore()).isNotNull();
    return spec.semaphore();
  }

  private void assertRejected(String name, String... fragments) {
    assertThatThrownBy(() -> factory.create(method(name)))
        .isInstanceOf(LocksmithConfigurationException.class)
        .hasMessageContaining(FIXTURE)
        .hasMessageContaining(name)
        .hasMessageContainingAll(fragments);
  }

  @Nested
  @DisplayName("accepts")
  class Accepts {

    @Test
    @DisplayName("defaults: key template parsed, REENTRANT, wait ZERO, no lease, THROW, no handler")
    void defaults() {
      LockSpec spec = lock("defaults");

      assertThat(KeyTemplate.evaluate(spec.key(), method("defaults"), new Object[] {"42"}))
          .isEqualTo("user:42");
      assertThat(spec.type()).isEqualTo(LockType.REENTRANT);
      assertThat(spec.waitTime()).isEqualTo(Duration.ZERO);
      assertThat(spec.leaseTime()).isNull();
      assertThat(spec.onFailure()).isEqualTo(OnFailure.THROW);
      assertThat(spec.handlerType()).isNull();
    }

    @Test
    @DisplayName("type WRITE")
    void type() {
      assertThat(lock("write").type()).isEqualTo(LockType.WRITE);
    }

    @Test
    @DisplayName("waitTime 5s")
    void waitSimple() {
      assertThat(lock("waitSimple").waitTime()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("waitTime PT5S")
    void waitIso() {
      assertThat(lock("waitIso").waitTime()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("leaseTime 30s")
    void lease() {
      assertThat(lock("lease").leaseTime()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("onFailure SKIP")
    void skip() {
      assertThat(lock("skip").onFailure()).isEqualTo(OnFailure.SKIP);
    }

    @Test
    @DisplayName("onFailure HANDLER with its handler type")
    void handler() {
      LockSpec spec = lock("handler");

      assertThat(spec.onFailure()).isEqualTo(OnFailure.HANDLER);
      assertThat(spec.handlerType()).isEqualTo(MarkerHandler.class);
    }

    @Test
    @DisplayName("an annotation declared on the interface")
    void fromInterface() {
      assertThat(lock("fromInterface").key().getExpressionString()).isEqualTo("iface");
    }

    @Test
    @DisplayName("an unannotated method gives a spec with both parts null")
    void unannotated() {
      assertThat(factory.create(method("unannotated"))).isEqualTo(new MethodSpec(null, null));
    }

    @Test
    @DisplayName("isAnnotated is true for lock, interface and semaphore, false for none")
    void isAnnotated() {
      assertThat(MethodSpecFactory.isAnnotated(method("defaults"))).isTrue();
      assertThat(MethodSpecFactory.isAnnotated(method("fromInterface"))).isTrue();
      assertThat(MethodSpecFactory.isAnnotated(method("semaphore"))).isTrue();
      assertThat(MethodSpecFactory.isAnnotated(method("unannotated"))).isFalse();
    }
  }

  @Nested
  @DisplayName("rejects")
  class Rejects {

    @Test
    @DisplayName("blank key, naming class, method and key")
    void blankKey() {
      assertRejected("blankKey", "key must not be blank");
    }

    @Test
    @DisplayName("unparsable key, with the SpEL parse error")
    void unparsableKey() {
      String spelError =
          catchThrowableOfType(ParseException.class, () -> KeyTemplate.parse("a:#{#id +}"))
              .getMessage();

      assertRejected("unparsableKey", "key [a:#{#id +}]", spelError);
    }

    @Test
    @DisplayName("unknown variable, with the variable and the -parameters hint")
    void unknownVariable() {
      assertRejected("unknownVariable", "#nope", "compile with -parameters or use #p0");
    }

    @Test
    @DisplayName("unparsable waitTime, with attribute and value")
    void badWait() {
      assertRejected("badWait", "waitTime", "[soon]");
    }

    @Test
    @DisplayName("negative waitTime, with attribute and value")
    void negativeWait() {
      assertRejected("negativeWait", "waitTime", "[-5s]", "must not be negative");
    }

    @Test
    @DisplayName("unparsable leaseTime, with attribute and value")
    void badLease() {
      assertRejected("badLease", "leaseTime", "[later]");
    }

    @Test
    @DisplayName("negative leaseTime, with attribute and value")
    void negativeLease() {
      assertRejected("negativeLease", "leaseTime", "[-1s]", "must be positive");
    }

    @Test
    @DisplayName("zero leaseTime, with attribute and value")
    void zeroLease() {
      assertRejected("zeroLease", "leaseTime", "[0s]", "must be positive");
    }

    @Test
    @DisplayName("onFailure HANDLER without handler, naming the handler type")
    void handlerMissing() {
      assertRejected("handlerMissing", "HANDLER", LockFailureHandler.class.getName());
    }

    @Test
    @DisplayName("handler set with onFailure THROW")
    void handlerWithThrow() {
      assertRejected("handlerWithThrow", MarkerHandler.class.getName(), "onFailure is THROW");
    }

    @Test
    @DisplayName("handler set with onFailure SKIP")
    void handlerWithSkip() {
      assertRejected("handlerWithSkip", MarkerHandler.class.getName(), "onFailure is SKIP");
    }
  }

  @Nested
  @DisplayName("accepts @DistributedSemaphore")
  class AcceptsSemaphore {

    @Test
    @DisplayName("defaults: key template, literal permits, wait ZERO, lease from properties, THROW")
    void defaults() {
      SemaphoreSpec spec = semaphore("semaphore");

      assertThat(KeyTemplate.evaluate(spec.key(), method("semaphore"), new Object[] {"7"}))
          .isEqualTo("report:7");
      assertThat(spec.permits()).isEqualTo(2);
      assertThat(spec.waitTime()).isEqualTo(Duration.ZERO);
      assertThat(spec.leaseTime()).isEqualTo(DEFAULT_SEMAPHORE_LEASE);
      assertThat(spec.onFailure()).isEqualTo(OnFailure.THROW);
      assertThat(spec.handlerType()).isNull();
    }

    @Test
    @DisplayName("permits from a ${...} placeholder resolved through the Environment")
    void placeholder() {
      assertThat(semaphore("semaphorePlaceholder").permits()).isEqualTo(4);
    }

    @Test
    @DisplayName("explicit waitTime 3s and leaseTime 30s")
    void timed() {
      SemaphoreSpec spec = semaphore("semaphoreTimed");

      assertThat(spec.waitTime()).isEqualTo(Duration.ofSeconds(3));
      assertThat(spec.leaseTime()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("onFailure HANDLER with a SemaphoreFailureHandler type")
    void handler() {
      SemaphoreSpec spec = semaphore("semaphoreHandler");

      assertThat(spec.onFailure()).isEqualTo(OnFailure.HANDLER);
      assertThat(spec.handlerType()).isEqualTo(MarkerSemaphoreHandler.class);
    }

    @Test
    @DisplayName("both annotations on one method give a spec with both parts set")
    void both() {
      MethodSpec spec = factory.create(method("both"));

      assertThat(spec.lock()).isNotNull();
      assertThat(spec.lock().key().getExpressionString()).isEqualTo("l");
      assertThat(spec.lock().type()).isEqualTo(LockType.WRITE);
      assertThat(spec.semaphore()).isNotNull();
      assertThat(spec.semaphore().key().getExpressionString()).isEqualTo("s");
      assertThat(spec.semaphore().permits()).isEqualTo(3);
    }
  }

  @Nested
  @DisplayName("rejects @DistributedSemaphore")
  class RejectsSemaphore {

    private static final String PREFIX = "@DistributedSemaphore on ";

    @Test
    @DisplayName("blank key, naming class, method and key")
    void blankKey() {
      assertRejected("semaphoreBlankKey", PREFIX, "key must not be blank");
    }

    @Test
    @DisplayName("unparsable key, with the SpEL parse error")
    void unparsableKey() {
      assertRejected("semaphoreUnparsableKey", PREFIX, "key [a:#{#id +}] is not a valid template");
    }

    @Test
    @DisplayName("unknown variable, with the variable and the -parameters hint")
    void unknownVariable() {
      assertRejected("semaphoreUnknownVariable", "#nope", "compile with -parameters or use #p0");
    }

    @Test
    @DisplayName("permits not a number, with the value")
    void permitsNotNumber() {
      assertRejected("permitsNotNumber", PREFIX, "permits [many] is not an integer");
    }

    @Test
    @DisplayName("permits placeholder resolving to a non-number, with resolved and original text")
    void permitsPlaceholderNotNumber() {
      assertRejected(
          "permitsPlaceholderNotNumber",
          PREFIX,
          "permits [x] (from [${reports.bad}]) is not an integer");
    }

    @Test
    @DisplayName("permits 0, with the value")
    void permitsZero() {
      assertRejected("permitsZero", PREFIX, "permits [0] must be greater than zero");
    }

    @Test
    @DisplayName("permits negative, with the value")
    void permitsNegative() {
      assertRejected("permitsNegative", PREFIX, "permits [-2] must be greater than zero");
    }

    @Test
    @DisplayName("unresolvable permits placeholder, with the placeholder")
    void permitsUnresolvable() {
      assertRejected(
          "permitsUnresolvable", PREFIX, "permits [${reports.missing}] cannot be resolved");
    }

    @Test
    @DisplayName("unparsable waitTime, with attribute and value")
    void badWait() {
      assertRejected("semaphoreBadWait", PREFIX, "waitTime", "[soon]");
    }

    @Test
    @DisplayName("negative waitTime, with attribute and value")
    void negativeWait() {
      assertRejected("semaphoreNegativeWait", PREFIX, "waitTime", "[-5s]", "must not be negative");
    }

    @Test
    @DisplayName("unparsable leaseTime, with attribute and value")
    void badLease() {
      assertRejected("semaphoreBadLease", PREFIX, "leaseTime", "[later]");
    }

    @Test
    @DisplayName("zero leaseTime, with attribute and value")
    void zeroLease() {
      assertRejected("semaphoreZeroLease", PREFIX, "leaseTime", "[0s]", "must be positive");
    }

    @Test
    @DisplayName("negative leaseTime, with attribute and value")
    void negativeLease() {
      assertRejected("semaphoreNegativeLease", PREFIX, "leaseTime", "[-1s]", "must be positive");
    }

    @Test
    @DisplayName("onFailure HANDLER without handler, naming SemaphoreFailureHandler")
    void handlerMissing() {
      assertRejected(
          "semaphoreHandlerMissing", PREFIX, "HANDLER", SemaphoreFailureHandler.class.getName());
    }

    @Test
    @DisplayName("handler set with onFailure THROW")
    void handlerWithThrow() {
      assertRejected(
          "semaphoreHandlerWithThrow",
          PREFIX,
          MarkerSemaphoreHandler.class.getName(),
          "onFailure is THROW");
    }
  }

  /** Meta-annotated with {@code @Async}, so it counts as {@code @Async}. */
  @Async
  @Retention(RetentionPolicy.RUNTIME)
  @interface Background {}

  /** A {@code CompletionStage} subtype that a completed {@code CompletableFuture} is not. */
  static class CustomFuture<T> extends CompletableFuture<T> {}

  @SuppressWarnings("unused")
  static class ReturnTypes {
    @DistributedLock(key = "k")
    CompletableFuture<String> completableFuture() {
      return null;
    }

    @DistributedLock(key = "k", onFailure = OnFailure.SKIP)
    CompletionStage<String> completionStageSkip() {
      return null;
    }

    @DistributedLock(key = "k")
    CustomFuture<String> customFutureThrow() {
      return null;
    }

    @DistributedLock(key = "k", onFailure = OnFailure.SKIP)
    CustomFuture<String> customFutureSkip() {
      return null;
    }

    @DistributedSemaphore(key = "k", permits = "1", onFailure = OnFailure.SKIP)
    CustomFuture<String> semaphoreCustomFutureSkip() {
      return null;
    }

    @DistributedLock(key = "k")
    Mono<String> mono() {
      return null;
    }

    @DistributedLock(key = "k")
    Flux<String> flux() {
      return null;
    }

    @DistributedLock(key = "k")
    Publisher<String> reactiveStreamsPublisher() {
      return null;
    }

    @DistributedLock(key = "k")
    Flow.Publisher<String> flowPublisher() {
      return null;
    }

    @DistributedSemaphore(key = "k", permits = "1")
    Mono<String> semaphoreMono() {
      return null;
    }

    @DistributedLock(key = "k")
    Object suspending(String id, Continuation<? super String> continuation) {
      return null;
    }

    @DistributedLock(key = "k")
    Future<String> future() {
      return null;
    }

    @DistributedLock(key = "k")
    ForkJoinTask<String> forkJoinTask() {
      return null;
    }

    @DistributedSemaphore(key = "k", permits = "1")
    Future<String> semaphoreFuture() {
      return null;
    }

    @Async
    @DistributedLock(key = "k")
    Future<String> asyncFuture() {
      return null;
    }

    @Background
    @DistributedLock(key = "k")
    Future<String> metaAsyncFuture() {
      return null;
    }
  }

  @Async
  @SuppressWarnings("unused")
  static class AsyncReturnTypes {
    @DistributedLock(key = "k")
    Future<String> future() {
      return null;
    }
  }

  @Nested
  @DisplayName("return types")
  class ReturnTypeRules {

    private static final String LOCK_PREFIX =
        "@DistributedLock on " + ReturnTypes.class.getName() + ".";

    private static final String SEMAPHORE_PREFIX =
        "@DistributedSemaphore on " + ReturnTypes.class.getName() + ".";

    private static Method returnTypes(String name) {
      return Arrays.stream(ReturnTypes.class.getDeclaredMethods())
          .filter(m -> m.getName().equals(name))
          .findFirst()
          .orElseThrow();
    }

    private void assertRejectedWith(String name, String message) {
      assertThatThrownBy(() -> factory.create(returnTypes(name)))
          .isInstanceOf(LocksmithConfigurationException.class)
          .hasMessage(message);
    }

    private static String reactive(String prefix, String name, Class<?> type) {
      return prefix
          + name
          + ": reactive return types are not supported, got ["
          + type.getName()
          + "]; return CompletableFuture or CompletionStage";
    }

    private static String untracked(String prefix, String name, Class<?> type) {
      return prefix
          + name
          + ": return type "
          + type.getName()
          + " cannot be tracked to completion; return CompletableFuture, or mark the method @Async";
    }

    private static String skipCannotHold(String prefix, String name) {
      return prefix
          + name
          + ": onFailure SKIP returns a CompletableFuture, which return type ["
          + CustomFuture.class.getName()
          + "] cannot hold; declare CompletableFuture or CompletionStage, or use onFailure HANDLER";
    }

    @Test
    @DisplayName("accepts CompletableFuture, and CompletionStage with SKIP")
    void acceptsStages() {
      assertThat(factory.create(returnTypes("completableFuture")).lock()).isNotNull();
      assertThat(factory.create(returnTypes("completionStageSkip")).lock()).isNotNull();
    }

    @Test
    @DisplayName("accepts a CompletableFuture subtype when onFailure is not SKIP")
    void acceptsCustomFutureWithoutSkip() {
      assertThat(factory.create(returnTypes("customFutureThrow")).lock()).isNotNull();
    }

    @Test
    @DisplayName("rejects SKIP on a CompletableFuture subtype, for both annotations")
    void rejectsSkipOnCustomFuture() {
      assertRejectedWith("customFutureSkip", skipCannotHold(LOCK_PREFIX, "customFutureSkip"));
      assertRejectedWith(
          "semaphoreCustomFutureSkip",
          skipCannotHold(SEMAPHORE_PREFIX, "semaphoreCustomFutureSkip"));
    }

    @Test
    @DisplayName("rejects Mono, Flux, Reactive Streams Publisher and Flow.Publisher")
    void rejectsReactive() {
      assertRejectedWith("mono", reactive(LOCK_PREFIX, "mono", Mono.class));
      assertRejectedWith("flux", reactive(LOCK_PREFIX, "flux", Flux.class));
      assertRejectedWith(
          "reactiveStreamsPublisher",
          reactive(LOCK_PREFIX, "reactiveStreamsPublisher", Publisher.class));
      assertRejectedWith(
          "flowPublisher", reactive(LOCK_PREFIX, "flowPublisher", Flow.Publisher.class));
      assertRejectedWith("semaphoreMono", reactive(SEMAPHORE_PREFIX, "semaphoreMono", Mono.class));
    }

    @Test
    @DisplayName("rejects a Kotlin suspend function")
    void rejectsSuspend() {
      assertRejectedWith(
          "suspending",
          LOCK_PREFIX
              + "suspending: Kotlin suspend functions are not supported; return CompletableFuture"
              + " from a function that is not suspend");
    }

    @Test
    @DisplayName("rejects Future and ForkJoinTask without @Async, for both annotations")
    void rejectsUntrackedFuture() {
      assertRejectedWith("future", untracked(LOCK_PREFIX, "future", Future.class));
      assertRejectedWith(
          "forkJoinTask", untracked(LOCK_PREFIX, "forkJoinTask", ForkJoinTask.class));
      assertRejectedWith(
          "semaphoreFuture", untracked(SEMAPHORE_PREFIX, "semaphoreFuture", Future.class));
    }

    @Test
    @DisplayName("accepts Future with @Async on the method, meta-annotated, or on the class")
    void acceptsAsyncFuture() throws NoSuchMethodException {
      assertThat(factory.create(returnTypes("asyncFuture")).lock()).isNotNull();
      assertThat(factory.create(returnTypes("metaAsyncFuture")).lock()).isNotNull();
      assertThat(factory.create(AsyncReturnTypes.class.getDeclaredMethod("future")).lock())
          .isNotNull();
    }
  }
}
