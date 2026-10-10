package in.riido.locksmith.semaphore;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

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
import java.util.concurrent.CyclicBarrier;
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
import org.redisson.RedissonShutdownException;
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

    @Test
    @DisplayName(
        "sixteen threads on two instances racing a new key's first acquire: exactly five get in")
    void racingFirstAcquireSetsCountOnce() throws Exception {
      SemaphoreOperations first = operations(client1, new NoOpLocksmithMetrics());
      SemaphoreOperations second = operations(client2, new NoOpLocksmithMetrics());
      int racers = 16;
      CyclicBarrier start = new CyclicBarrier(racers);
      CountDownLatch release = new CountDownLatch(1);
      AtomicInteger acquired = new AtomicInteger();
      List<Future<?>> all = new ArrayList<>();
      try {
        for (int i = 0; i < racers; i++) {
          SemaphoreOperations operations = i % 2 == 0 ? first : second;
          all.add(
              executor.submit(
                  () -> {
                    start.await(5, SECONDS);
                    try (PermitHandle permit = operations.key(key).permits(5).acquire()) {
                      if (permit.acquired()) {
                        acquired.incrementAndGet();
                        release.await(5, SECONDS);
                      }
                    }
                    return null;
                  }));
        }
        await()
            .atMost(Duration.ofSeconds(5))
            .until(() -> acquired.get() == 5 && all.stream().filter(Future::isDone).count() == 11);
        assertThat(first.availablePermits(key)).isZero();
      } finally {
        release.countDown();
      }
      for (Future<?> future : all) {
        future.get(10, SECONDS);
      }
      assertThat(acquired.get()).isEqualTo(5);
      assertThat(client1.getPermitExpirableSemaphore("locksmith:semaphore:" + key).getPermits())
          .isEqualTo(5);
      assertThat(first.availablePermits(key)).isEqualTo(5);
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

    @Test
    @DisplayName(
        "after the set of held permits is lost while the semaphore is full, the next acquire"
            + " restores the count")
    void restoresCountAfterHeldPermitsLost() {
      SemaphoreOperations semaphores = operations(client1, new NoOpLocksmithMetrics());
      PermitHandle a = semaphores.key(key).permits(2).acquire();
      PermitHandle b = semaphores.key(key).permits(2).acquire();
      assertThat(semaphores.availablePermits(key)).isZero();

      client2.getKeys().delete("{locksmith:semaphore:" + key + "}:timeout");

      try (PermitHandle permit = semaphores.key(key).permits(2).acquire()) {
        assertThat(permit.acquired()).isTrue();
      }
      a.close();
      b.close();
    }
  }

  @Nested
  @DisplayName("waiting")
  class Waiting {

    @Test
    @DisplayName(
        "a waiter whose client shuts down during the wait throws RedissonShutdownException, not"
            + " waiting forever")
    void clientShutDownWhileWaiting() throws Exception {
      try (PermitHandle held =
          operations(client1, new NoOpLocksmithMetrics()).key(key).permits(1).acquire()) {
        assertThat(held.acquired()).isTrue();
        RedissonClient client = newClient();
        SemaphoreOperations semaphores = operations(client, new NoOpLocksmithMetrics());
        Future<PermitHandle> waiter =
            executor.submit(
                () -> semaphores.key(key).permits(1).waitTime(Duration.ofSeconds(5)).acquire());
        Thread.sleep(300);

        client.shutdown();

        // Redisson's shutdown never completes a pending wait; before the fix this waiter never
        // returned, not even after its wait time.
        assertThatThrownBy(() -> waiter.get(10, SECONDS))
            .hasCauseInstanceOf(RedissonShutdownException.class);
      }
    }
  }

  @Nested
  @DisplayName("interrupted caller")
  class Interrupted {

    @Test
    @DisplayName("interrupted before acquire: unacquired, flag kept, and no permit left taken")
    void noPermitLeftTaken() {
      // Redisson's blocking tryAcquire throws here yet still takes a permit, until its lease ends.
      int permits = 50;
      SemaphoreOperations semaphores = operations(client1, new NoOpLocksmithMetrics());
      semaphores.key(key).permits(permits).acquire().close();
      for (int i = 0; i < permits; i++) {
        Thread.currentThread().interrupt();
        try {
          PermitHandle handle =
              semaphores.key(key).permits(permits).waitTime(Duration.ofSeconds(1)).acquire();
          assertThat(Thread.interrupted()).as("interrupt flag").isTrue();
          handle.close();
        } finally {
          Thread.interrupted();
        }
      }

      await()
          .atMost(Duration.ofSeconds(5))
          .until(() -> semaphores.availablePermits(key) == permits);
    }

    @Test
    @DisplayName(
        "a pre-interrupted try-once caller never blocks another instance's concurrent try-once")
    void preInterruptedDoesNotBlockAnotherInstance() throws Exception {
      // Before the fix, sending tryAcquireAsync while already interrupted still took the permit
      // for a moment before the cancel released it again, so instance 2's try-once could lose the
      // race for the single permit.
      SemaphoreOperations semaphores1 = operations(client1, new NoOpLocksmithMetrics());
      SemaphoreOperations semaphores2 = operations(client2, new NoOpLocksmithMetrics());
      int trials = 200;
      for (int i = 0; i < trials; i++) {
        String trialKey = key + ":" + i;
        // Warms up each instance's permit-count cache for this key, so the race below sends only
        // the tryAcquireAsync call, the same way the lock's race sends only tryLockAsync.
        semaphores1.key(trialKey).permits(1).acquire().close();
        semaphores2.key(trialKey).permits(1).acquire().close();
        CyclicBarrier barrier = new CyclicBarrier(2);
        // A's own flag is set on the pooled thread that runs it, and cleared there afterwards, so
        // it never leaks onto the executor thread for a later trial.
        Future<PermitHandle> aFuture =
            executor.submit(
                () -> {
                  barrier.await();
                  Thread.currentThread().interrupt();
                  try {
                    return semaphores1.key(trialKey).permits(1).acquire();
                  } finally {
                    Thread.interrupted();
                  }
                });
        Future<PermitHandle> bFuture =
            executor.submit(
                () -> {
                  barrier.await();
                  return semaphores2.key(trialKey).permits(1).acquire();
                });

        PermitHandle aHandle = aFuture.get(5, SECONDS);
        PermitHandle bHandle = bFuture.get(5, SECONDS);

        assertThat(bHandle.acquired()).as("trial %d", i).isTrue();

        aHandle.close();
        bHandle.close();
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
  @DisplayName("client shutdown during releases (slow)")
  class ReleaseAtShutdown {

    @Test
    @DisplayName(
        "220 releases sent to a paused Redis: every close() returns once the client shuts down,"
            + " and no release WARN carries a stack trace")
    void everyCloseReturns() throws Exception {
      // More releases than the last 100 calls Redisson's shutdown settles: before the fix, 24 of
      // 220 close() calls never returned, interrupted or not, even after Redis answered again.
      int count = 220;
      RedissonClient client = newClient();
      SemaphoreOperations semaphores = operations(client, new NoOpLocksmithMetrics());
      List<PermitHandle> handles = new ArrayList<>();
      for (int i = 0; i < count; i++) {
        handles.add(semaphores.key(key).permits(count).acquire());
      }
      assertThat(handles).allMatch(PermitHandle::acquired);
      Logger logger = (Logger) LoggerFactory.getLogger(PermitHandle.class);
      ListAppender<ILoggingEvent> appender = new ListAppender<>();
      appender.start();
      logger.addAppender(appender);
      CountDownLatch started = new CountDownLatch(count);
      CountDownLatch returned = new CountDownLatch(count);
      // Redis holds even CLIENT UNPAUSE until a pause of all clients runs out, so it is short.
      assertThat(redis.execInContainer("redis-cli", "CLIENT", "PAUSE", "5000", "ALL").getStdout())
          .startsWith("OK");
      try {
        for (PermitHandle handle : handles) {
          Thread closer =
              new Thread(
                  () -> {
                    started.countDown();
                    handle.close();
                    returned.countDown();
                  });
          // A close() that never returns must not keep the test JVM alive.
          closer.setDaemon(true);
          closer.start();
        }
        assertThat(started.await(5, SECONDS)).isTrue();
        Thread.sleep(200);

        client.shutdown();

        assertThat(returned.await(5, SECONDS)).as("every close() returned").isTrue();
        assertThat(appender.list)
            .filteredOn(e -> e.getLevel() == Level.WARN)
            .isNotEmpty()
            .allSatisfy(
                warning -> {
                  assertThat(warning.getFormattedMessage())
                      .contains("because the Redisson client is shutting down");
                  assertThat(warning.getThrowableProxy()).isNull();
                });
      } finally {
        logger.detachAppender(appender);
        // Answers once the pause has run out, so the next test finds Redis serving.
        redis.execInContainer("redis-cli", "PING");
      }
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
              "Permit [locksmith:semaphore:" + key + "] was reported as not held at release, ")
          .contains("(lease 1000ms). Possible causes: the lease ran out");
    }
  }
}
