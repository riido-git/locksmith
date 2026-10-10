package in.riido.locksmith.autoconfigure;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import com.redis.testcontainers.RedisContainer;
import in.riido.locksmith.DistributedLock;
import in.riido.locksmith.DockerAvailableCondition;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import kotlin.Unit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.testcontainers.utility.DockerImageName;

/**
 * Annotated {@code @Async} methods that return a future or Kotlin's {@code Unit}, end to end: a
 * Boot context without AspectJ against a real Redis. Spring's {@code @Async} runs first, so the
 * lock covers the method body on the worker thread.
 */
@ExtendWith(DockerAvailableCondition.class)
@DisplayName("@Async annotated methods end to end")
class AsyncAnnotationPathIntegrationTest {

  private static final String FULL_KEY = "locksmith:lock:order:42";
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

  private RLock lock;

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

  /** The JVM signature of Kotlin's {@code fun process(id: String): Unit?}. */
  static class AsyncUnitService {
    @Async
    @DistributedLock(key = "order:#{#id}")
    public Unit process(String id) throws InterruptedException {
      block(id);
      return Unit.INSTANCE;
    }
  }

  /** Signals that the body runs, then blocks until the test lets it finish. */
  private static String block(String id) throws InterruptedException {
    started.countDown();
    assertThat(finish.await(10, SECONDS)).isTrue();
    return "processed " + id + " on " + Thread.currentThread().getName();
  }

  /** Waits until another instance can take the lock, then gives it back. */
  private void assertLockReleasedEventually() throws InterruptedException {
    assertThat(lock.tryLock(RELEASE_WAIT.toSeconds(), SECONDS)).as("lock released").isTrue();
    lock.unlock();
  }

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
          assertLockReleasedEventually();
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

  @Test
  @DisplayName("a Kotlin Unit: the call returns null and the lock covers the body on the worker")
  void asyncKotlinUnitHoldsLockForBody() throws Exception {
    runner
        .withBean(AsyncUnitService.class)
        .run(
            context -> {
              AsyncUnitService service = context.getBean(AsyncUnitService.class);

              assertThat(service.process("42")).isNull();
              assertThat(started.await(10, SECONDS)).isTrue();

              assertThat(lock.tryLock()).isFalse();

              finish.countDown();
              assertLockReleasedEventually();
            });
  }
}
