package in.riido.locksmith.lock;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
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
import in.riido.locksmith.LockType;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.IntStream;
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
import org.redisson.api.RBlockingQueue;
import org.redisson.api.RTopic;
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

    @Test
    @DisplayName(
        "a waiter whose client shuts down during the wait throws RedissonShutdownException, not"
            + " waiting forever")
    void clientShutDownWhileWaiting() throws Exception {
      Holder holder = new Holder(() -> locks1.key(key).acquire());
      RedissonClient client = newClient(null);
      LockOperations locks = operations(client, new NoOpLocksmithMetrics());
      Future<LockHandle> waiter =
          executor.submit(() -> locks.key(key).waitTime(Duration.ofSeconds(5)).acquire());
      Thread.sleep(300);

      client.shutdown();

      // Redisson's shutdown never completes a pending wait; before the fix this waiter never
      // returned, not even after its wait time.
      assertThatThrownBy(() -> waiter.get(10, SECONDS))
          .hasCauseInstanceOf(RedissonShutdownException.class);
      holder.release();
    }
  }

  @Nested
  @DisplayName("interrupted caller")
  class Interrupted {

    @Test
    @DisplayName("interrupted before acquire: unacquired, flag kept, and no lock left in Redis")
    void noLockLeftBehind() {
      // Redisson's blocking tryLock throws here yet still takes the lock, which its watchdog then
      // keeps forever; fifty attempts made that happen every time before the fix.
      int attempts = 50;
      for (int i = 0; i < attempts; i++) {
        Thread.currentThread().interrupt();
        try {
          LockHandle handle = locks1.key(key + ":" + i).waitTime(Duration.ofSeconds(1)).acquire();
          assertThat(Thread.interrupted()).as("interrupt flag").isTrue();
          handle.close();
        } finally {
          Thread.interrupted();
        }
      }

      await()
          .atMost(Duration.ofSeconds(5))
          .until(
              () ->
                  IntStream.range(0, attempts)
                      .noneMatch(i -> locks2.isLocked(key + ":" + i, LockType.REENTRANT)));
    }

    @Test
    @DisplayName("an interrupt racing a winning attempt leaves no lock without a handle")
    void interruptRacingAWin() throws Exception {
      // Each caller is interrupted at a random point of its round trip, so some interrupts land
      // just as the attempt wins; before the fix about 1 in 60 such callers kept the lock.
      int trials = 2000;
      List<String> unacquired = new ArrayList<>();
      for (int i = 0; i < trials; i++) {
        String trialKey = key + ":" + i;
        AtomicReference<LockHandle> result = new AtomicReference<>();
        CountDownLatch go = new CountDownLatch(1);
        Thread caller =
            new Thread(
                () -> {
                  try {
                    go.await();
                  } catch (InterruptedException e) {
                    return;
                  }
                  LockHandle handle = locks1.key(trialKey).acquire();
                  Thread.interrupted();
                  result.set(handle);
                  handle.close();
                });
        caller.start();
        go.countDown();
        long spinNanos = ThreadLocalRandom.current().nextLong(600_000);
        long start = System.nanoTime();
        while (System.nanoTime() - start < spinNanos) {
          Thread.onSpinWait();
        }
        caller.interrupt();
        caller.join();
        if (result.get() != null && !result.get().acquired()) {
          unacquired.add(trialKey);
        }
      }

      // Redisson releases a lock that a cancelled attempt still wins shortly afterwards.
      await()
          .atMost(Duration.ofSeconds(10))
          .until(() -> unacquired.stream().noneMatch(k -> locks2.isLocked(k, LockType.REENTRANT)));
    }

    @Test
    @DisplayName(
        "a pre-interrupted try-once caller never blocks another instance's concurrent try-once")
    void preInterruptedDoesNotBlockAnotherInstance() throws Exception {
      // Before the fix, sending tryLockAsync while already interrupted still took the lock for a
      // moment before the cancel released it again, so instance 2's try-once could lose the race.
      int trials = 200;
      for (int i = 0; i < trials; i++) {
        String trialKey = key + ":" + i;
        CyclicBarrier barrier = new CyclicBarrier(2);
        // A's own flag is set on the pooled thread that runs it, and cleared there afterwards, so
        // it never leaks onto the executor thread for a later trial.
        Future<LockHandle> aFuture =
            executor.submit(
                () -> {
                  barrier.await();
                  Thread.currentThread().interrupt();
                  try {
                    return locks1.key(trialKey).acquire();
                  } finally {
                    Thread.interrupted();
                  }
                });
        Future<LockHandle> bFuture =
            executor.submit(
                () -> {
                  barrier.await();
                  return locks2.key(trialKey).acquire();
                });

        LockHandle aHandle = aFuture.get(5, SECONDS);
        LockHandle bHandle = bFuture.get(5, SECONDS);

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
      RedissonClient client = newClient(null);
      LockOperations locks = operations(client, new NoOpLocksmithMetrics());
      List<LockHandle> handles = new ArrayList<>();
      for (int i = 0; i < count; i++) {
        handles.add(locks.key(key + ":" + i).acquire());
      }
      assertThat(handles).allMatch(LockHandle::acquired);
      Logger logger = (Logger) LoggerFactory.getLogger(LockHandle.class);
      ListAppender<ILoggingEvent> appender = new ListAppender<>();
      appender.start();
      logger.addAppender(appender);
      CountDownLatch started = new CountDownLatch(count);
      CountDownLatch returned = new CountDownLatch(count);
      // Redis holds even CLIENT UNPAUSE until a pause of all clients runs out, so it is short.
      assertThat(redis.execInContainer("redis-cli", "CLIENT", "PAUSE", "5000", "ALL").getStdout())
          .startsWith("OK");
      try {
        for (LockHandle handle : handles) {
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
  @DisplayName("threads")
  class Threads {

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
                      locks1.key(key).acquire();
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
    @DisplayName(
        "on a Redisson listener thread: a waiting acquire throws at once; try-once works, and"
            + " close() has released the lock when it returns")
    void listenerThread() throws Exception {
      record Run(String thread, Throwable waiting, Duration took, int acquired, int stillLocked) {}
      LockHandle holder = locks2.key(key + ":held").acquire();
      RTopic topic = client1.getTopic(key + ":topic");
      CompletableFuture<Run> result = new CompletableFuture<>();
      topic.addListener(
          String.class,
          (channel, message) -> {
            long start = System.nanoTime();
            Throwable waiting = null;
            try {
              locks1.key(key + ":held").waitTime(Duration.ofSeconds(3)).acquire();
            } catch (RuntimeException e) {
              waiting = e;
            }
            Duration took = Duration.ofNanos(System.nanoTime() - start);
            int acquired = 0;
            int stillLocked = 0;
            for (int i = 0; i < 100; i++) {
              try (LockHandle handle = locks1.key(key).acquire()) {
                acquired += handle.acquired() ? 1 : 0;
              }
              stillLocked += locks1.isLocked(key, LockType.REENTRANT) ? 1 : 0;
            }
            result.complete(
                new Run(Thread.currentThread().getName(), waiting, took, acquired, stillLocked));
          });
      topic.publish("go");
      Run run = result.get(20, SECONDS);
      holder.close();
      topic.removeAllListeners();

      assertThat(run.thread()).startsWith("redisson-").doesNotStartWith("redisson-netty");
      assertThat(run.waiting())
          .isExactlyInstanceOf(IllegalStateException.class)
          .hasMessageStartingWith("Locksmith cannot wait for a lock or permit on Redisson thread");
      assertThat(run.took()).isLessThan(Duration.ofMillis(500));
      assertThat(run.acquired()).isEqualTo(100);
      assertThat(run.stillLocked()).isZero();
    }

    @Test
    @DisplayName(
        "on Redisson's timer thread: even a try-once acquire throws at once, sends nothing")
    void timerThread() throws Exception {
      LockHandle holder = locks2.key(key + ":held").acquire();
      CompletableFuture<Object[]> result = new CompletableFuture<>();
      // A Redisson wait that runs out is completed by the timer, so its continuation runs there.
      client1
          .getLock("locksmith:lock:" + key + ":held")
          .tryLockAsync(100, MILLISECONDS)
          .thenRun(
              () -> {
                Throwable thrown = null;
                try {
                  locks1.key(key).acquire();
                } catch (RuntimeException e) {
                  thrown = e;
                }
                result.complete(new Object[] {Thread.currentThread().getName(), thrown});
              });
      Object[] outcome = result.get(10, SECONDS);
      holder.close();

      assertThat((String) outcome[0]).startsWith("redisson-timer");
      assertThat((Throwable) outcome[1])
          .isExactlyInstanceOf(IllegalStateException.class)
          .hasMessageStartingWith("Locksmith cannot run on Redisson's timer thread");
      assertThat(locks2.isLocked(key, LockType.REENTRANT)).isFalse();
    }

    @Test
    @DisplayName("closed on another thread: released, with no WARN")
    void closedOnAnotherThread() throws Exception {
      Logger logger = (Logger) LoggerFactory.getLogger(LockHandle.class);
      ListAppender<ILoggingEvent> appender = new ListAppender<>();
      appender.start();
      logger.addAppender(appender);
      try {
        LockHandle handle = locks1.key(key).acquire();
        assertThat(handle.acquired()).isTrue();

        executor.submit(handle::close).get(5, SECONDS);

        assertThat(locks2.isLocked(key, LockType.REENTRANT)).isFalse();
        assertThat(appender.list).noneMatch(e -> e.getLevel() == Level.WARN);
      } finally {
        logger.detachAppender(appender);
      }
    }

    @Test
    @DisplayName("closed on an interrupted thread: released, flag kept, with no WARN")
    void closedOnInterruptedThread() {
      Logger logger = (Logger) LoggerFactory.getLogger(LockHandle.class);
      ListAppender<ILoggingEvent> appender = new ListAppender<>();
      appender.start();
      logger.addAppender(appender);
      try {
        LockHandle handle = locks1.key(key).acquire();
        Thread.currentThread().interrupt();

        handle.close();

        assertThat(Thread.interrupted()).as("interrupt flag").isTrue();
        assertThat(locks2.isLocked(key, LockType.REENTRANT)).isFalse();
        assertThat(appender.list).noneMatch(e -> e.getLevel() == Level.WARN);
      } finally {
        Thread.interrupted();
        logger.detachAppender(appender);
      }
    }

    @Test
    @DisplayName("interrupted while waiting: unacquired, flag restored, and a late win is undone")
    void interruptedLateWinReleased() throws Exception {
      Holder holder = new Holder(() -> locks2.key(key).acquire());
      CompletableFuture<Boolean[]> outcome = new CompletableFuture<>();
      Thread waiter =
          new Thread(
              () -> {
                LockHandle handle = locks1.key(key).waitTime(Duration.ofSeconds(10)).acquire();
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
