package in.riido.locksmith.semaphore;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.redis.testcontainers.RedisContainer;
import in.riido.locksmith.DockerAvailableCondition;
import in.riido.locksmith.autoconfigure.LocksmithProperties;
import in.riido.locksmith.metrics.LocksmithMetrics;
import in.riido.locksmith.metrics.MicrometerLocksmithMetrics;
import in.riido.locksmith.metrics.NoOpLocksmithMetrics;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.slf4j.LoggerFactory;
import org.testcontainers.utility.DockerImageName;

@ExtendWith(DockerAvailableCondition.class)
@DisplayName("SemaphoreOperations against Redis")
class SemaphoreOperationsIntegrationTest {

  private static final LocksmithProperties PROPERTIES = new LocksmithProperties(null, null, null);

  private static RedisContainer redis;
  private static RedissonClient client1;
  private static RedissonClient client2;

  private final ExecutorService executor = Executors.newCachedThreadPool();
  private String key;

  @BeforeAll
  static void startRedis() {
    redis = new RedisContainer(DockerImageName.parse("redis:7-alpine"));
    redis.start();
    client1 = newClient();
    client2 = newClient();
  }

  @AfterAll
  static void stopRedis() {
    client1.shutdown();
    client2.shutdown();
    redis.stop();
  }

  @BeforeEach
  void newKey() {
    key = "it:" + UUID.randomUUID();
  }

  @AfterEach
  void stopExecutor() {
    executor.shutdownNow();
  }

  private static RedissonClient newClient() {
    Config config = new Config();
    config
        .useSingleServer()
        .setAddress("redis://" + redis.getHost() + ":" + redis.getFirstMappedPort());
    return Redisson.create(config);
  }

  private static SemaphoreOperations operations(RedissonClient client, LocksmithMetrics metrics) {
    return new SemaphoreOperations(client, PROPERTIES, metrics);
  }

  @Nested
  @DisplayName("capacity")
  class Capacity {

    @Test
    @DisplayName("permits 2, four threads: at most two hold at once and all four eventually run")
    void atMostTwoHoldAtOnce() throws Exception {
      SemaphoreOperations semaphores = operations(client1, new NoOpLocksmithMetrics());
      AtomicInteger holding = new AtomicInteger();
      AtomicInteger highWater = new AtomicInteger();
      CountDownLatch start = new CountDownLatch(1);
      List<Future<Boolean>> results = new ArrayList<>();
      for (int i = 0; i < 4; i++) {
        results.add(
            executor.submit(
                () -> {
                  start.await();
                  try (PermitHandle permit =
                      semaphores.key(key).permits(2).waitTime(Duration.ofSeconds(10)).acquire()) {
                    if (!permit.acquired()) {
                      return false;
                    }
                    highWater.accumulateAndGet(holding.incrementAndGet(), Math::max);
                    Thread.sleep(300);
                    holding.decrementAndGet();
                    return true;
                  }
                }));
      }

      start.countDown();

      for (Future<Boolean> result : results) {
        assertThat(result.get(15, SECONDS)).isTrue();
      }
      assertThat(highWater.get()).isEqualTo(2);
      assertThat(semaphores.availablePermits(key)).isEqualTo(2);
    }
  }

  @Nested
  @DisplayName("permit count")
  class PermitCount {

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void captureLog() {
      logger = (Logger) LoggerFactory.getLogger(SemaphoreOperations.class);
      appender = new ListAppender<>();
      appender.start();
      logger.addAppender(appender);
    }

    @AfterEach
    void releaseLog() {
      logger.detachAppender(appender);
    }

    @Test
    @DisplayName("a second SemaphoreOperations on another client raises 2 to 5, logging one INFO")
    void secondInstanceRaisesCount() {
      SemaphoreOperations first = operations(client1, new NoOpLocksmithMetrics());
      SemaphoreOperations second = operations(client2, new NoOpLocksmithMetrics());

      try (PermitHandle permit = first.key(key).permits(2).acquire()) {
        assertThat(permit.acquired()).isTrue();
      }
      assertThat(first.availablePermits(key)).isEqualTo(2);

      try (PermitHandle permit = second.key(key).permits(5).acquire()) {
        assertThat(permit.acquired()).isTrue();
      }

      assertThat(second.availablePermits(key)).isEqualTo(5);
      assertThat(first.availablePermits(key)).isEqualTo(5);
      List<ILoggingEvent> infos =
          appender.list.stream().filter(e -> e.getLevel() == Level.INFO).toList();
      assertThat(infos).hasSize(1);
      assertThat(infos.get(0).getFormattedMessage())
          .isEqualTo("Semaphore [locksmith:semaphore:" + key + "] permits changed from 2 to 5");
    }
  }

  @Nested
  @DisplayName("lost Redis state")
  class LostState {

    @Test
    @DisplayName("after the key is deleted in Redis, the next acquire re-creates it with its count")
    void recreatesDeletedSemaphore() {
      SemaphoreOperations semaphores = operations(client1, new NoOpLocksmithMetrics());
      try (PermitHandle permit = semaphores.key(key).permits(2).acquire()) {
        assertThat(permit.acquired()).isTrue();
      }

      client2.getPermitExpirableSemaphore("locksmith:semaphore:" + key).delete();

      try (PermitHandle permit = semaphores.key(key).permits(2).acquire()) {
        assertThat(permit.acquired()).isTrue();
        assertThat(semaphores.availablePermits(key)).isEqualTo(1);
      }
    }
  }

  @Nested
  @DisplayName("metrics")
  class Metrics {

    @Test
    @DisplayName(
        "records locksmith.acquire (semaphore, acquired) and locksmith.held (semaphore) once each")
    void acquireAndHeldRecorded() {
      SimpleMeterRegistry registry = new SimpleMeterRegistry();
      SemaphoreOperations semaphores =
          operations(client1, new MicrometerLocksmithMetrics(registry));

      try (PermitHandle permit = semaphores.key(key).permits(1).acquire()) {
        assertThat(permit.acquired()).isTrue();
      }

      Timer acquire =
          registry
              .find("locksmith.acquire")
              .tag("primitive", "semaphore")
              .tag("outcome", "acquired")
              .timer();
      Timer held = registry.find("locksmith.held").tag("primitive", "semaphore").timer();
      assertThat(acquire).isNotNull();
      assertThat(acquire.count()).isEqualTo(1);
      assertThat(held).isNotNull();
      assertThat(held.count()).isEqualTo(1);
    }
  }

  @Nested
  @Tag("slow")
  @DisplayName("lease (slow)")
  class Lease {

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void captureLog() {
      logger = (Logger) LoggerFactory.getLogger(PermitHandle.class);
      appender = new ListAppender<>();
      appender.start();
      logger.addAppender(appender);
    }

    @AfterEach
    void releaseLog() {
      logger.detachAppender(appender);
    }

    @Test
    @DisplayName("an outrun 1s lease frees the permit; close() after 2s WARNs and does not throw")
    void leaseOutrun() throws Exception {
      SemaphoreOperations first = operations(client1, new NoOpLocksmithMetrics());
      SemaphoreOperations second = operations(client2, new NoOpLocksmithMetrics());
      PermitHandle permit = first.key(key).permits(1).leaseTime(Duration.ofSeconds(1)).acquire();
      assertThat(permit.acquired()).isTrue();

      Thread.sleep(2000);
      try (PermitHandle other = second.key(key).permits(1).acquire()) {
        assertThat(other.acquired()).as("permit free after the lease ran out").isTrue();
        assertThatNoException().isThrownBy(permit::close);
      }

      List<ILoggingEvent> warnings =
          appender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
      assertThat(warnings).hasSize(1);
      assertThat(warnings.get(0).getFormattedMessage())
          .startsWith(
              "Permit [locksmith:semaphore:" + key + "] was no longer held at release after ")
          .contains("(lease 1000ms); another instance may have run concurrently");
    }
  }
}
