package in.riido.locksmith.autoconfigure;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.redis.testcontainers.RedisContainer;
import in.riido.locksmith.DistributedLock;
import in.riido.locksmith.DistributedSemaphore;
import in.riido.locksmith.DockerAvailableCondition;
import in.riido.locksmith.OnFailure;
import in.riido.locksmith.lock.LockHandle;
import in.riido.locksmith.lock.LockNotAcquiredException;
import in.riido.locksmith.semaphore.PermitHandle;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.redisson.Redisson;
import org.redisson.api.RBlockingQueue;
import org.redisson.api.RLock;
import org.redisson.api.RPermitExpirableSemaphore;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.testcontainers.utility.DockerImageName;

/**
 * Annotated methods that return a future, end to end: a Boot context without AspectJ against a real
 * Redis.
 */
@ExtendWith(DockerAvailableCondition.class)
@DisplayName("Async annotated methods end to end")
class AsyncAnnotationPathIntegrationTest {

  private static final String FULL_KEY = "locksmith:lock:order:42";
  private static final String SEMAPHORE_KEY = "locksmith:semaphore:report:42";
  private static final Duration RELEASE_WAIT = Duration.ofSeconds(5);

  private static RedisContainer redis;
  private static RedissonClient otherInstance;

  /** Opened by a blocking method body once it runs; the body then waits for {@link #finish}. */
  private static CountDownLatch started;

  private static CountDownLatch finish;

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(AopAutoConfiguration.class, LocksmithAutoConfiguration.class))
          .withUserConfiguration(UserConfiguration.class);

  private final List<Logger> loggers =
      List.of(
          (Logger) LoggerFactory.getLogger(LockHandle.class),
          (Logger) LoggerFactory.getLogger(PermitHandle.class));

  private ListAppender<ILoggingEvent> appender;
  private RLock lock;
  private RPermitExpirableSemaphore semaphore;

  @BeforeAll
  static void startRedis() {
    redis = new RedisContainer(DockerImageName.parse("redis:7-alpine"));
    redis.start();
    otherInstance = newClient();
  }

  @AfterAll
  static void stopRedis() {
    otherInstance.shutdown();
    redis.stop();
  }

  @BeforeEach
  void setUp() {
    otherInstance.getKeys().flushall();
    started = new CountDownLatch(1);
    finish = new CountDownLatch(1);
    lock = otherInstance.getLock(FULL_KEY);
    semaphore = otherInstance.getPermitExpirableSemaphore(SEMAPHORE_KEY);
    appender = new ListAppender<>();
    appender.start();
    loggers.forEach(logger -> logger.addAppender(appender));
  }

  @AfterEach
  void tearDown() {
    loggers.forEach(logger -> logger.detachAppender(appender));
  }

  private static RedissonClient newClient() {
    Config config = new Config();
    config
        .useSingleServer()
        .setAddress("redis://" + redis.getHost() + ":" + redis.getFirstMappedPort());
    return Redisson.create(config);
  }

  @Configuration(proxyBeanMethods = false)
  @EnableAsync
  static class UserConfiguration {

    @Bean(destroyMethod = "shutdown")
    RedissonClient redissonClient() {
      return newClient();
    }

    @Bean
    AsyncOrderService asyncOrderService() {
      return new AsyncOrderService();
    }

    @Bean
    SyncOrderService syncOrderService() {
      return new SyncOrderService();
    }

    @Bean
    StageOrderService stageOrderService(SyncOrderService sync) {
      return new StageOrderService(sync);
    }
  }

  /** Spring's {@code @Async} runs these on a worker thread, outside Locksmith's advice. */
  static class AsyncOrderService {
    @Async
    @DistributedLock(key = "order:#{#id}")
    public Future<String> processAsync(String id) throws InterruptedException {
      return CompletableFuture.completedFuture(block(id));
    }

    @Async
    @DistributedLock(key = "order:#{#id}")
    public CompletableFuture<String> processCompletable(String id) throws InterruptedException {
      return CompletableFuture.completedFuture(block(id));
    }
  }

  static class SyncOrderService {
    @DistributedLock(key = "order:#{#id}")
    public String process(String id) {
      return "processed " + id;
    }
  }

  /** Returns the future the test hands in, so the test decides when the work completes. */
  static class StageOrderService {
    private final SyncOrderService sync;

    StageOrderService(SyncOrderService sync) {
      this.sync = sync;
    }

    @DistributedLock(key = "order:#{#id}")
    public CompletableFuture<String> process(String id, CompletableFuture<String> work) {
      return work;
    }

    @DistributedSemaphore(key = "report:#{#id}", permits = "1")
    @DistributedLock(key = "order:#{#id}")
    public CompletableFuture<String> processBoth(String id, CompletableFuture<String> work) {
      return work;
    }

    @DistributedSemaphore(key = "report:#{#id}", permits = "1")
    @DistributedLock(key = "order:#{#id}")
    public CompletableFuture<String> throwing(String id) {
      throw new IllegalStateException("boom");
    }

    @DistributedSemaphore(key = "report:#{#id}", permits = "1")
    @DistributedLock(key = "order:#{#id}")
    public CompletableFuture<String> returningNull(String id) {
      return null;
    }

    @DistributedLock(key = "order:#{#id}", onFailure = OnFailure.SKIP)
    public CompletableFuture<String> processOrSkip(String id) {
      return CompletableFuture.completedFuture("processed " + id);
    }

    @DistributedLock(key = "order:#{#id}")
    public CompletableFuture<String> nestedOnWorker(String id) {
      return CompletableFuture.supplyAsync(() -> sync.process(id));
    }

    @DistributedLock(key = "order:#{#id}")
    public CompletableFuture<String> nestedOnCaller(String id) {
      return CompletableFuture.completedFuture(sync.process(id));
    }
  }

  /** Signals that the body runs, then blocks until the test lets it finish. */
  private static String block(String id) throws InterruptedException {
    started.countDown();
    assertThat(finish.await(10, SECONDS)).isTrue();
    return "processed " + id + " on " + Thread.currentThread().getName();
  }

  private List<ILoggingEvent> warnings() {
    return appender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
  }

  /** Waits until another instance can take the lock, then gives it back. */
  private void assertLockReleasedEventually() throws InterruptedException {
    assertThat(lock.tryLock(RELEASE_WAIT.toSeconds(), SECONDS)).as("lock released").isTrue();
    lock.unlock();
  }

  /** Waits until another instance can take the lock and the permit, then gives the lock back. */
  private void assertReleasedEventually() throws InterruptedException {
    assertLockReleasedEventually();
    await().atMost(RELEASE_WAIT).until(() -> semaphore.availablePermits() == 1);
  }

  /** Checks, without waiting, that another instance can take the lock and the permit. */
  private void assertReleasedNow() {
    assertThat(lock.isLocked()).as("lock released").isFalse();
    assertThat(semaphore.availablePermits()).as("permit released").isEqualTo(1);
  }

  private void assertHeld() {
    assertThat(lock.tryLock()).as("lock free").isFalse();
    assertThat(semaphore.availablePermits()).as("free permits").isZero();
  }

  @Nested
  @DisplayName("with @Async")
  class WithAsync {

    @Test
    @DisplayName("a plain Future: the lock covers the body on the worker thread")
    void asyncFutureHoldsLockForBody() throws Exception {
      runner.run(
          context -> {
            AsyncOrderService service = context.getBean(AsyncOrderService.class);

            Future<String> result = service.processAsync("42");
            assertThat(started.await(10, SECONDS)).isTrue();

            assertThat(lock.tryLock()).isFalse();

            finish.countDown();
            assertThat(result.get(10, SECONDS))
                .startsWith("processed 42 on ")
                .doesNotEndWith(Thread.currentThread().getName());
            assertThat(lock.tryLock()).isTrue();
            lock.unlock();
          });
    }

    @Test
    @DisplayName("a CompletableFuture: the lock covers the body on the worker thread")
    void asyncCompletableFutureHoldsLockForBody() throws Exception {
      runner.run(
          context -> {
            AsyncOrderService service = context.getBean(AsyncOrderService.class);

            CompletableFuture<String> result = service.processCompletable("42");
            assertThat(started.await(10, SECONDS)).isTrue();

            assertThat(lock.tryLock()).isFalse();

            finish.countDown();
            assertThat(result.get(10, SECONDS)).startsWith("processed 42 on ");
            assertLockReleasedEventually();
          });
    }
  }

  @Nested
  @DisplayName("returning a CompletableFuture")
  class Stage {

    @Test
    @DisplayName("a second caller is blocked until the future completes, not until the return")
    void blockedUntilCompletion() {
      runner.run(
          context -> {
            StageOrderService service = context.getBean(StageOrderService.class);
            CompletableFuture<String> work = new CompletableFuture<>();

            assertThat(service.process("42", work)).isSameAs(work);

            assertThat(lock.tryLock()).isFalse();
            assertThatThrownBy(() -> service.process("42", new CompletableFuture<>()))
                .isInstanceOf(LockNotAcquiredException.class)
                .hasMessageContaining(FULL_KEY);

            work.complete("done");
            assertLockReleasedEventually();
          });
    }

    @Test
    @DisplayName("called in a Redisson callback: the future fails and no lock is left behind")
    void refusedOnRedissonThread() {
      runner.run(
          context -> {
            StageOrderService service = context.getBean(StageOrderService.class);
            // takeAsync completes only after the offer, so the call runs on a Redisson thread.
            RBlockingQueue<String> trigger =
                context.getBean(RedissonClient.class).getBlockingQueue("trigger");
            AtomicReference<String> callingThread = new AtomicReference<>();

            CompletableFuture<String> result =
                trigger
                    .takeAsync()
                    .thenCompose(
                        ignored -> {
                          callingThread.set(Thread.currentThread().getName());
                          return service.process("42", CompletableFuture.completedFuture("done"));
                        })
                    .toCompletableFuture();
            trigger.offer("go");

            assertThatThrownBy(() -> result.get(10, SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("Sync methods can't be invoked from async/rx/reactive listeners");
            assertThat(callingThread.get()).startsWith("redisson-netty");
            assertThat(lock.isLocked()).as("lock left behind").isFalse();
          });
    }

    @Test
    @DisplayName("lock and permit are released when the future completes normally")
    void releasedOnSuccess() {
      runner.run(
          context -> {
            CompletableFuture<String> work = new CompletableFuture<>();
            context.getBean(StageOrderService.class).processBoth("42", work);
            assertHeld();

            work.complete("done");

            assertReleasedEventually();
            assertThat(warnings()).isEmpty();
          });
    }

    @Test
    @DisplayName("lock and permit are released when the future completes exceptionally")
    void releasedOnExceptionalCompletion() {
      runner.run(
          context -> {
            CompletableFuture<String> work = new CompletableFuture<>();
            context.getBean(StageOrderService.class).processBoth("42", work);
            assertHeld();

            work.completeExceptionally(new IllegalStateException("boom"));

            assertReleasedEventually();
            assertThat(warnings()).isEmpty();
          });
    }

    @Test
    @DisplayName("lock and permit are released when the caller cancels the returned future")
    void releasedOnCancel() {
      runner.run(
          context -> {
            CompletableFuture<String> returned =
                context
                    .getBean(StageOrderService.class)
                    .processBoth("42", new CompletableFuture<>());
            assertHeld();

            returned.cancel(true);

            assertReleasedEventually();
            assertThat(warnings()).isEmpty();
          });
    }

    @Test
    @DisplayName("completion on a Redisson I/O thread releases lock and permit with no WARN")
    void completionOnAnotherThread() {
      runner.run(
          context -> {
            CompletableFuture<String> work = new CompletableFuture<>();
            AtomicReference<String> completingThread = new AtomicReference<>();
            context.getBean(StageOrderService.class).processBoth("42", work);
            assertHeld();

            // takeAsync completes only after the offer, so the future completes on a Redisson
            // thread.
            RBlockingQueue<String> trigger = otherInstance.getBlockingQueue("trigger");
            trigger
                .takeAsync()
                .thenRun(
                    () -> {
                      completingThread.set(Thread.currentThread().getName());
                      work.complete("done");
                    });
            trigger.offer("go");

            assertReleasedEventually();
            assertThat(completingThread.get()).startsWith("redisson-netty");
            assertThat(warnings()).isEmpty();
          });
    }

    @Test
    @DisplayName("the method throws: lock and permit are released before the exception arrives")
    void releasedWhenMethodThrows() {
      runner.run(
          context -> {
            StageOrderService service = context.getBean(StageOrderService.class);

            assertThatThrownBy(() -> service.throwing("42"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");

            assertReleasedNow();
          });
    }

    @Test
    @DisplayName("the method returns null: lock and permit are released before it returns")
    void releasedWhenMethodReturnsNull() {
      runner.run(
          context -> {
            assertThat(context.getBean(StageOrderService.class).returningNull("42")).isNull();

            assertReleasedNow();
          });
    }

    @Test
    @DisplayName("SKIP on a held lock returns a completed future holding null")
    void skipReturnsCompletedNull() {
      runner.run(
          context -> {
            lock.lock();
            try {
              CompletableFuture<String> result =
                  context.getBean(StageOrderService.class).processOrSkip("42");

              assertThat(result).isCompletedWithValue(null);
            } finally {
              lock.unlock();
            }
          });
    }

    @Test
    @DisplayName("THROW on a held lock throws synchronously, before any future exists")
    void throwIsSynchronous() {
      runner.run(
          context -> {
            StageOrderService service = context.getBean(StageOrderService.class);
            lock.lock();
            try {
              assertThatThrownBy(() -> service.process("42", new CompletableFuture<>()))
                  .isInstanceOf(LockNotAcquiredException.class)
                  .hasMessageContaining(FULL_KEY);
            } finally {
              lock.unlock();
            }
          });
    }

    @Test
    @DisplayName("a nested synchronous lock on the same key is not reentrant, on any thread")
    void nestedSameKeyNotReentrant() {
      runner.run(
          context -> {
            StageOrderService service = context.getBean(StageOrderService.class);

            assertThatThrownBy(() -> service.nestedOnWorker("42").get(10, SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(LockNotAcquiredException.class);
            assertThatThrownBy(() -> service.nestedOnCaller("42"))
                .isInstanceOf(LockNotAcquiredException.class);

            assertLockReleasedEventually();
          });
    }
  }
}
