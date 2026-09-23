package in.riido.locksmith.lock;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.redis.testcontainers.RedisContainer;
import in.riido.locksmith.DockerAvailableCondition;
import in.riido.locksmith.LockType;
import in.riido.locksmith.autoconfigure.LocksmithProperties;
import in.riido.locksmith.metrics.LocksmithMetrics;
import in.riido.locksmith.metrics.MicrometerLocksmithMetrics;
import in.riido.locksmith.metrics.NoOpLocksmithMetrics;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
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
import org.redisson.api.RBlockingQueue;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.slf4j.LoggerFactory;
import org.testcontainers.utility.DockerImageName;

@ExtendWith(DockerAvailableCondition.class)
@DisplayName("LockOperations against Redis")
class LockOperationsIntegrationTest {

  private static final LocksmithProperties PROPERTIES = new LocksmithProperties(null, null, null);

  private static RedisContainer redis;
  private static RedissonClient client1;
  private static RedissonClient client2;
  private static LockOperations locks1;
  private static LockOperations locks2;

  private final ExecutorService executor = Executors.newCachedThreadPool();
  private String key;

  @BeforeAll
  static void startRedis() {
    redis = new RedisContainer(DockerImageName.parse("redis:7-alpine"));
    redis.start();
    client1 = newClient(null);
    client2 = newClient(null);
    locks1 = operations(client1, new NoOpLocksmithMetrics());
    locks2 = operations(client2, new NoOpLocksmithMetrics());
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

  private static RedissonClient newClient(Long watchdogTimeoutMillis) {
    Config config = new Config();
    config
        .useSingleServer()
        .setAddress("redis://" + redis.getHost() + ":" + redis.getFirstMappedPort());
    if (watchdogTimeoutMillis != null) {
      config.setLockWatchdogTimeout(watchdogTimeoutMillis);
    }
    return Redisson.create(config);
  }

  private static LockOperations operations(RedissonClient client, LocksmithMetrics metrics) {
    return new LockOperations(client, PROPERTIES, metrics);
  }

  /** Acquires on a background thread and holds until {@link #release()}, then closes there. */
  private final class Holder {
    private final CountDownLatch attempted = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final Future<?> done;
    private volatile boolean acquired;

    Holder(Supplier<LockHandle> acquire) throws InterruptedException {
      done =
          executor.submit(
              () -> {
                try (LockHandle handle = acquire.get()) {
                  acquired = handle.acquired();
                  attempted.countDown();
                  release.await();
                }
                return null;
              });
      assertThat(attempted.await(5, SECONDS)).isTrue();
      assertThat(acquired).as("holder acquired").isTrue();
    }

    void releaseAfter(Duration delay) {
      CompletableFuture.delayedExecutor(delay.toMillis(), MILLISECONDS).execute(release::countDown);
    }

    void release() throws Exception {
      release.countDown();
      done.get(5, SECONDS);
    }
  }

  /** Acquires and closes on another thread, returning whether it was acquired. */
  private boolean acquiredOnOtherThread(Supplier<LockHandle> acquire) throws Exception {
    return executor
        .submit(
            () -> {
              try (LockHandle handle = acquire.get()) {
                return handle.acquired();
              }
            })
        .get(10, SECONDS);
  }

  @Nested
  @DisplayName("exclusivity")
  class Exclusivity {

    @Test
    @DisplayName("a second thread sees acquired() == false while the first holds the key")
    void secondThreadNotAcquired() throws Exception {
      Holder holder = new Holder(() -> locks1.key(key).acquire());

      try (LockHandle second = locks1.key(key).acquire()) {
        assertThat(second.acquired()).isFalse();
      }

      holder.release();
      try (LockHandle afterRelease = locks1.key(key).acquire()) {
        assertThat(afterRelease.acquired()).isTrue();
      }
    }

    @Test
    @DisplayName("a second Redisson client sees acquired() == false while the first holds the key")
    void secondClientNotAcquired() {
      try (LockHandle first = locks1.key(key).acquire();
          LockHandle second = locks2.key(key).acquire()) {
        assertThat(first.acquired()).isTrue();
        assertThat(second.acquired()).isFalse();
        assertThat(locks2.isLocked(key, LockType.REENTRANT)).isTrue();
      }
      assertThat(locks2.isLocked(key, LockType.REENTRANT)).isFalse();
    }
  }

  @Nested
  @DisplayName("read and write")
  class ReadWrite {

    @Test
    @DisplayName("two read locks on one key coexist")
    void readersCoexist() throws Exception {
      Holder reader = new Holder(() -> locks1.key(key).type(LockType.READ).acquire());

      try (LockHandle secondReader = locks2.key(key).type(LockType.READ).acquire()) {
        assertThat(secondReader.acquired()).isTrue();
      }

      reader.release();
    }

    @Test
    @DisplayName("a write lock is refused while a reader holds, then waits for the reader")
    void writerWaitsForReaders() throws Exception {
      Holder reader = new Holder(() -> locks1.key(key).type(LockType.READ).acquire());

      try (LockHandle refused = locks2.key(key).type(LockType.WRITE).acquire()) {
        assertThat(refused.acquired()).isFalse();
      }

      reader.releaseAfter(Duration.ofSeconds(1));
      long start = System.nanoTime();
      try (LockHandle writer =
          locks2.key(key).type(LockType.WRITE).waitTime(Duration.ofSeconds(3)).acquire()) {
        assertThat(writer.acquired()).isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - start))
            .isGreaterThan(Duration.ofMillis(800));
      }
      reader.release();
    }

    @Test
    @DisplayName("a held write lock excludes readers")
    void writerExcludesReaders() throws Exception {
      try (LockHandle writer = locks1.key(key).type(LockType.WRITE).acquire()) {
        assertThat(writer.acquired()).isTrue();

        assertThat(acquiredOnOtherThread(() -> locks1.key(key).type(LockType.READ).acquire()))
            .isFalse();
        try (LockHandle otherClientReader = locks2.key(key).type(LockType.READ).acquire()) {
          assertThat(otherClientReader.acquired()).isFalse();
        }
      }
    }

    @Test
    @DisplayName("a held REENTRANT lock neither blocks nor corrupts READ or WRITE on the same key")
    void reentrantSeparateFromReadWrite() throws Exception {
      Holder reentrant = new Holder(() -> locks1.key(key).acquire());

      try (LockHandle reader = locks2.key(key).type(LockType.READ).acquire()) {
        assertThat(reader.acquired()).isTrue();
      }
      try (LockHandle writer = locks2.key(key).type(LockType.WRITE).acquire()) {
        assertThat(writer.acquired()).isTrue();
        assertThat(writer.key()).isEqualTo("locksmith:rwlock:" + key);
        assertThat(client2.getKeys().countExists("locksmith:lock:" + key)).isEqualTo(1);
        assertThat(client2.getKeys().countExists("locksmith:rwlock:" + key)).isEqualTo(1);
      }

      assertThat(locks2.isLocked(key, LockType.REENTRANT)).isTrue();
      assertThat(locks2.isLocked(key, LockType.WRITE)).isFalse();
      try (LockHandle otherClient = locks2.key(key).acquire()) {
        assertThat(otherClient.acquired()).isFalse();
      }
      reentrant.release();
      assertThat(locks2.isLocked(key, LockType.REENTRANT)).isFalse();
    }
  }

  @Nested
  @DisplayName("waiting")
  class Waiting {

    @Test
    @DisplayName("a waiter with waitTime 3s acquires when the holder releases after 1s")
    void waitThenAcquire() throws Exception {
      Holder holder = new Holder(() -> locks1.key(key).acquire());
      holder.releaseAfter(Duration.ofSeconds(1));

      long start = System.nanoTime();
      try (LockHandle waiter = locks2.key(key).waitTime(Duration.ofSeconds(3)).acquire()) {
        Duration waited = Duration.ofNanos(System.nanoTime() - start);
        assertThat(waiter.acquired()).isTrue();
        assertThat(waited).isBetween(Duration.ofMillis(800), Duration.ofSeconds(3));
      }
      holder.release();
    }

    @Test
    @DisplayName("a waiter with waitTime 1s gives up unacquired while the holder keeps it 3s")
    void waitThenGiveUp() throws Exception {
      Holder holder = new Holder(() -> locks1.key(key).acquire());
      holder.releaseAfter(Duration.ofSeconds(3));

      long start = System.nanoTime();
      try (LockHandle waiter = locks2.key(key).waitTime(Duration.ofSeconds(1)).acquire()) {
        Duration waited = Duration.ofNanos(System.nanoTime() - start);
        assertThat(waiter.acquired()).isFalse();
        assertThat(waited).isBetween(Duration.ofMillis(900), Duration.ofMillis(2500));
      }
      holder.release();
    }
  }

  @Nested
  @DisplayName("metrics")
  class Metrics {

    @Test
    @DisplayName("records locksmith.acquire (lock, acquired) and locksmith.held (lock) once each")
    void acquireAndHeldRecorded() {
      SimpleMeterRegistry registry = new SimpleMeterRegistry();
      LockOperations locks = operations(client1, new MicrometerLocksmithMetrics(registry));

      try (LockHandle handle = locks.key(key).acquire()) {
        assertThat(handle.acquired()).isTrue();
      }

      Timer acquire =
          registry
              .find("locksmith.acquire")
              .tag("primitive", "lock")
              .tag("outcome", "acquired")
              .timer();
      Timer held = registry.find("locksmith.held").tag("primitive", "lock").timer();
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
      logger = (Logger) LoggerFactory.getLogger(LockHandle.class);
      appender = new ListAppender<>();
      appender.start();
      logger.addAppender(appender);
    }

    @AfterEach
    void releaseLog() {
      logger.detachAppender(appender);
    }

    @Test
    @DisplayName("renewal keeps a 5s hold exclusive with lockWatchdogTimeout 2000ms")
    void renewalKeepsLock() throws Exception {
      RedissonClient shortWatchdog = newClient(2000L);
      try {
        LockOperations renewing = operations(shortWatchdog, new NoOpLocksmithMetrics());
        long start = System.nanoTime();
        try (LockHandle holder = renewing.key(key).acquire()) {
          assertThat(holder.acquired()).isTrue();
          while (System.nanoTime() - start < Duration.ofSeconds(5).toNanos()) {
            try (LockHandle other = locks2.key(key).acquire()) {
              assertThat(other.acquired()).as("second client acquired during hold").isFalse();
            }
            Thread.sleep(250);
          }
        }
        try (LockHandle after = locks2.key(key).acquire()) {
          assertThat(after.acquired()).isTrue();
        }
      } finally {
        shortWatchdog.shutdown();
      }
    }

    @Test
    @DisplayName(
        "an outrun 1s fixed lease lets a second client in at 1.5s; close() WARNs, no throw")
    void fixedLeaseOutrun() throws Exception {
      LockHandle first = locks1.key(key).leaseTime(Duration.ofSeconds(1)).acquire();
      assertThat(first.acquired()).isTrue();

      Thread.sleep(1500);
      try (LockHandle second = locks2.key(key).acquire()) {
        assertThat(second.acquired()).isTrue();

        Thread.sleep(500);
        assertThatNoException().isThrownBy(first::close);
      }

      List<ILoggingEvent> warnings =
          appender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
      assertThat(warnings).hasSize(1);
      assertThat(warnings.get(0).getFormattedMessage())
          .startsWith("Lock [locksmith:lock:" + key + "] was no longer held at release after ")
          .contains("(fixed lease 1000ms); another instance may have run concurrently");
    }
  }

  @Nested
  @DisplayName("owned by a generated id")
  class Owned {

    @Test
    @DisplayName("on a Redisson I/O thread: throws at once and sends nothing to Redis")
    void refusedOnRedissonThread() throws Exception {
      record Attempt(String thread, Duration took, Throwable failure) {}
      // takeAsync completes only after the offer below, so the callback runs on a Redisson thread.
      RBlockingQueue<String> trigger = client1.getBlockingQueue(key + ":trigger");
      CompletableFuture<Attempt> result =
          trigger
              .takeAsync()
              .thenApply(
                  ignored -> {
                    long start = System.nanoTime();
                    Throwable failure = null;
                    try {
                      AsyncLockSupport.acquire(locks1.key(key));
                    } catch (RuntimeException e) {
                      failure = e;
                    }
                    return new Attempt(
                        Thread.currentThread().getName(),
                        Duration.ofNanos(System.nanoTime() - start),
                        failure);
                  })
              .toCompletableFuture();
      trigger.offer("go");
      Attempt attempt = result.get(10, SECONDS);

      assertThat(attempt.thread()).startsWith("redisson-netty");
      assertThat(attempt.failure())
          .isExactlyInstanceOf(IllegalStateException.class)
          .hasMessage("Sync methods can't be invoked from async/rx/reactive listeners");
      assertThat(attempt.took()).isLessThan(Duration.ofMillis(500));
      assertThat(locks2.isLocked(key, LockType.REENTRANT)).isFalse();
    }

    @Test
    @DisplayName("released from another thread, where a thread-owned lock would fail")
    void releasedFromAnotherThread() throws Exception {
      LockHandle handle = AsyncLockSupport.acquire(locks1.key(key));
      assertThat(handle.acquired()).isTrue();
      assertThat(locks2.isLocked(key, LockType.REENTRANT)).isTrue();

      executor.submit(() -> AsyncLockSupport.release(handle).toCompletableFuture().join()).get();

      assertThat(locks2.isLocked(key, LockType.REENTRANT)).isFalse();
    }

    @Test
    @DisplayName("not reentrant: a second owned acquire on the same thread is refused")
    void notReentrant() {
      LockHandle first = AsyncLockSupport.acquire(locks1.key(key));
      LockHandle second = AsyncLockSupport.acquire(locks1.key(key));

      assertThat(first.acquired()).isTrue();
      assertThat(second.acquired()).isFalse();
      first.close();
    }

    @Test
    @DisplayName("interrupted while waiting: unacquired, flag restored, and a late win is undone")
    void interruptedLateWinReleased() throws Exception {
      Holder holder = new Holder(() -> locks2.key(key).acquire());
      CompletableFuture<Boolean[]> outcome = new CompletableFuture<>();
      Thread waiter =
          new Thread(
              () -> {
                LockHandle handle =
                    AsyncLockSupport.acquire(locks1.key(key).waitTime(Duration.ofSeconds(10)));
                outcome.complete(
                    new Boolean[] {handle.acquired(), Thread.currentThread().isInterrupted()});
              });
      waiter.start();
      Thread.sleep(300);

      waiter.interrupt();
      Boolean[] result = outcome.get(5, SECONDS);
      assertThat(result[0]).as("acquired").isFalse();
      assertThat(result[1]).as("interrupt flag").isTrue();

      // The pending attempt takes the lock as soon as the holder lets go; it must be undone,
      // or the watchdog would keep it forever.
      holder.release();
      await()
          .pollDelay(Duration.ofSeconds(1))
          .atMost(Duration.ofSeconds(5))
          .until(() -> !locks2.isLocked(key, LockType.REENTRANT));
    }
  }
}
