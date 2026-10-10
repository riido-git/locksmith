package in.riido.locksmith.semaphore;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import in.riido.locksmith.LocksmithConfigurationException;
import in.riido.locksmith.autoconfigure.LocksmithProperties;
import in.riido.locksmith.metrics.LocksmithMetrics;
import in.riido.locksmith.metrics.LocksmithMetrics.Outcome;
import in.riido.locksmith.metrics.LocksmithMetrics.Primitive;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;
import org.redisson.RedissonShutdownException;
import org.redisson.api.RFuture;
import org.redisson.api.RPermitExpirableSemaphore;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisConnectionException;
import org.redisson.config.Config;
import org.redisson.misc.CompletableFutureWrapper;
import org.slf4j.LoggerFactory;

@DisplayName("SemaphoreOperations")
class SemaphoreOperationsTest {

  private static final String FULL_KEY = "test:semaphore:k";
  private static final Duration DEFAULT_LEASE = Duration.ofSeconds(90);

  private RedissonClient redisson;
  private RPermitExpirableSemaphore semaphore;
  private LocksmithMetrics metrics;
  private SemaphoreOperations operations;
  private Logger logger;
  private ListAppender<ILoggingEvent> appender;

  @BeforeEach
  void setUp() throws InterruptedException {
    redisson = mock(RedissonClient.class);
    semaphore = mock(RPermitExpirableSemaphore.class);
    when(redisson.getPermitExpirableSemaphore(FULL_KEY)).thenReturn(semaphore);
    when(semaphore.getPermitsAsync()).thenReturn(done(5));
    when(semaphore.setPermitsAsync(anyInt())).thenReturn(done(null));
    when(semaphore.releaseAsync(anyString())).thenReturn(done(null));
    when(semaphore.tryAcquireAsync(anyInt(), anyLong(), anyLong(), any()))
        .thenReturn(permit("id-1"));
    metrics = mock(LocksmithMetrics.class);
    operations =
        new SemaphoreOperations(
            redisson,
            new LocksmithProperties(
                null, "test:", new LocksmithProperties.Semaphore(DEFAULT_LEASE)),
            metrics);
    logger = (Logger) LoggerFactory.getLogger(SemaphoreOperations.class);
    appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void tearDown() {
    logger.detachAppender(appender);
    Thread.interrupted();
  }

  private static RFuture<List<String>> permit(String id) {
    return new CompletableFutureWrapper<>(id == null ? List.<String>of() : List.of(id));
  }

  private static <T> RFuture<T> done(T value) {
    return new CompletableFutureWrapper<>(value);
  }

  /** An attempt that Redisson completes just before the interrupted caller's cancel reaches it. */
  private static RFuture<List<String>> completesBeforeCancel(List<String> ids) {
    CompletableFuture<List<String>> attempt = new CompletableFuture<>();
    return new CompletableFutureWrapper<>(attempt) {
      @Override
      public boolean cancel(boolean mayInterruptIfRunning) {
        attempt.complete(ids);
        return super.cancel(mayInterruptIfRunning);
      }
    };
  }

  private List<ILoggingEvent> infos() {
    return appender.list.stream().filter(e -> e.getLevel() == Level.INFO).toList();
  }

  /** Runs the call on a thread with the given name; returns its result, or what it threw. */
  private static Object onThread(String name, Supplier<?> call) throws InterruptedException {
    AtomicReference<Object> outcome = new AtomicReference<>();
    Thread thread =
        new Thread(
            () -> {
              try {
                outcome.set(call.get());
              } catch (RuntimeException e) {
                outcome.set(e);
              }
            },
            name);
    thread.start();
    thread.join();
    return outcome.get();
  }

  /** Interrupts the calling thread when the mocked Redisson call runs, then returns the result. */
  private static <T> Answer<T> interruptingAnd(T result) {
    return invocation -> {
      Thread.currentThread().interrupt();
      return result;
    };
  }

  @Nested
  @DisplayName("key layout")
  class KeyLayout {

    @Test
    @DisplayName("prefixes the key as <keyPrefix>semaphore:<key> and reports it on the handle")
    void prefixesKey() {
      PermitHandle handle = operations.key("k").permits(5).acquire();

      verify(redisson).getPermitExpirableSemaphore(FULL_KEY);
      assertThat(handle.key()).isEqualTo(FULL_KEY);
    }
  }

  @Nested
  @DisplayName("permits")
  class Permits {

    @Test
    @DisplayName("acquire() without permits throws LocksmithConfigurationException, no Redis call")
    void missing() {
      assertThatThrownBy(() -> operations.key("k").acquire())
          .isInstanceOf(LocksmithConfigurationException.class)
          .hasMessageContaining(FULL_KEY)
          .hasMessageContaining("got 0");
      verifyNoInteractions(redisson);
    }

    @Test
    @DisplayName("acquire() with permits 0 throws LocksmithConfigurationException, no Redis call")
    void zero() {
      assertThatThrownBy(() -> operations.key("k").permits(0).acquire())
          .isInstanceOf(LocksmithConfigurationException.class)
          .hasMessageContaining(FULL_KEY)
          .hasMessageContaining("got 0");
      verifyNoInteractions(redisson);
    }

    @Test
    @DisplayName("acquire() with negative permits throws LocksmithConfigurationException")
    void negative() {
      assertThatThrownBy(() -> operations.key("k").permits(-3).acquire())
          .isInstanceOf(LocksmithConfigurationException.class)
          .hasMessageContaining("got -3");
      verifyNoInteractions(redisson);
    }
  }

  @Nested
  @DisplayName("durations passed to tryAcquireAsync")
  class Durations {

    @Test
    @DisplayName("defaults: wait 0 and the lease from locksmith.semaphore.lease-time, in ms")
    void defaults() throws InterruptedException {
      operations.key("k").permits(5).acquire();

      verify(semaphore).tryAcquireAsync(1, 0L, 90_000L, MILLISECONDS);
    }

    @Test
    @DisplayName("passes waitTime and leaseTime in milliseconds")
    void explicit() throws InterruptedException {
      operations
          .key("k")
          .permits(5)
          .waitTime(Duration.ofMillis(1500))
          .leaseTime(Duration.ofSeconds(30))
          .acquire();

      verify(semaphore).tryAcquireAsync(1, 1500L, 30_000L, MILLISECONDS);
    }

    @Test
    @DisplayName("rejects a negative waitTime with IllegalArgumentException in the builder")
    void rejectsNegativeWait() {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> operations.key("k").waitTime(Duration.ofMillis(-1)))
          .withMessageContaining("waitTime");
    }

    @Test
    @DisplayName("rejects a zero leaseTime with IllegalArgumentException in the builder")
    void rejectsZeroLease() {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> operations.key("k").leaseTime(Duration.ZERO))
          .withMessage("leaseTime must be at least one millisecond, got PT0S");
    }

    @Test
    @DisplayName("rejects a negative leaseTime with IllegalArgumentException in the builder")
    void rejectsNegativeLease() {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> operations.key("k").leaseTime(Duration.ofSeconds(-1)))
          .withMessageContaining("leaseTime");
    }

    @Test
    @DisplayName("rejects a sub-millisecond leaseTime, which would round to zero")
    void rejectsSubMillisecondLease() {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> operations.key("k").leaseTime(Duration.ofNanos(500)))
          .withMessageContaining("leaseTime must be at least one millisecond");
    }
  }

  @Nested
  @DisplayName("outcome")
  class Outcomes {

    @Test
    @DisplayName("records outcome ACQUIRED and returns a handle with the permit id")
    void acquired() {
      PermitHandle handle = operations.key("k").permits(5).acquire();

      assertThat(handle.acquired()).isTrue();
      assertThat(handle.permitId()).isEqualTo("id-1");
      verify(metrics)
          .recordAcquire(eq(Primitive.SEMAPHORE), eq(Outcome.ACQUIRED), any(Duration.class));
    }

    @Test
    @DisplayName("a null permit id records outcome SKIPPED and returns an unacquired handle")
    void skipped() throws InterruptedException {
      when(semaphore.tryAcquireAsync(anyInt(), anyLong(), anyLong(), any()))
          .thenReturn(permit(null));

      PermitHandle handle = operations.key("k").permits(5).acquire();

      assertThat(handle.acquired()).isFalse();
      assertThat(handle.permitId()).isNull();
      verify(metrics)
          .recordAcquire(eq(Primitive.SEMAPHORE), eq(Outcome.SKIPPED), any(Duration.class));
    }

    @Test
    @DisplayName(
        "interrupted: cancels the pending attempt, restores the flag, records INTERRUPTED,"
            + " unacquired")
    void interrupted() {
      CompletableFuture<List<String>> pending = new CompletableFuture<>();
      when(semaphore.tryAcquireAsync(anyInt(), anyLong(), anyLong(), any()))
          .thenAnswer(interruptingAnd(new CompletableFutureWrapper<>(pending)));

      PermitHandle handle = operations.key("k").permits(5).acquire();

      assertThat(Thread.currentThread().isInterrupted()).isTrue();
      assertThat(handle.acquired()).isFalse();
      // Cancelling is what makes Redisson release a permit the attempt still wins.
      assertThat(pending).isCancelled();
      verify(metrics)
          .recordAcquire(eq(Primitive.SEMAPHORE), eq(Outcome.INTERRUPTED), any(Duration.class));
    }

    @Test
    @DisplayName(
        "interrupted as the attempt wins: keeps the permit and the flag, records ACQUIRED, so no"
            + " permit is left without a handle")
    void interruptedAsAttemptWins() {
      when(semaphore.tryAcquireAsync(anyInt(), anyLong(), anyLong(), any()))
          .thenAnswer(interruptingAnd(completesBeforeCancel(List.of("id-1"))));

      PermitHandle handle = operations.key("k").permits(5).acquire();

      assertThat(Thread.currentThread().isInterrupted()).isTrue();
      assertThat(handle.permitId()).isEqualTo("id-1");
      verify(metrics)
          .recordAcquire(eq(Primitive.SEMAPHORE), eq(Outcome.ACQUIRED), any(Duration.class));
    }

    @Test
    @DisplayName(
        "interrupted as an empty attempt completes: the lost-key check stops too, INTERRUPTED,"
            + " no exception")
    void interruptedAsEmptyAttemptCompletes() {
      CompletableFuture<Integer> lostKeyCheck = new CompletableFuture<>();
      when(semaphore.getPermitsAsync())
          .thenReturn(done(5), new CompletableFutureWrapper<>(lostKeyCheck));
      when(semaphore.tryAcquireAsync(anyInt(), anyLong(), anyLong(), any()))
          .thenAnswer(interruptingAnd(completesBeforeCancel(List.of())));

      PermitHandle handle = operations.key("k").permits(5).acquire();

      assertThat(Thread.currentThread().isInterrupted()).isTrue();
      assertThat(handle.acquired()).isFalse();
      assertThat(lostKeyCheck).isCancelled();
      verify(metrics)
          .recordAcquire(eq(Primitive.SEMAPHORE), eq(Outcome.INTERRUPTED), any(Duration.class));
    }

    @Test
    @DisplayName(
        "interrupted while the count is set: unacquired, flag kept, INTERRUPTED, no exception")
    void interruptedWhileCountIsSet() {
      CompletableFuture<Integer> pending = new CompletableFuture<>();
      when(semaphore.getPermitsAsync())
          .thenAnswer(interruptingAnd(new CompletableFutureWrapper<>(pending)));

      PermitHandle handle = operations.key("k").permits(5).acquire();

      assertThat(Thread.currentThread().isInterrupted()).isTrue();
      assertThat(handle.acquired()).isFalse();
      assertThat(pending).isCancelled();
      verify(semaphore, never()).tryAcquireAsync(anyInt(), anyLong(), anyLong(), any());
      verify(metrics)
          .recordAcquire(eq(Primitive.SEMAPHORE), eq(Outcome.INTERRUPTED), any(Duration.class));
    }

    @Test
    @DisplayName(
        "interrupted before acquire is called: unacquired, flag kept, records INTERRUPTED, and"
            + " nothing reaches Redisson")
    void interruptedBeforeTheCall() {
      Thread.currentThread().interrupt();

      PermitHandle handle = operations.key("k").permits(5).acquire();

      assertThat(Thread.currentThread().isInterrupted()).isTrue();
      assertThat(handle.acquired()).isFalse();
      verify(metrics)
          .recordAcquire(eq(Primitive.SEMAPHORE), eq(Outcome.INTERRUPTED), any(Duration.class));
      verify(semaphore, never()).getPermitsAsync();
      verify(semaphore, never()).tryAcquireAsync(anyInt(), anyLong(), anyLong(), any());
      verify(semaphore, never()).setPermitsAsync(anyInt());
    }

    @Test
    @DisplayName(
        "on a Redisson executor thread: a waiting acquire throws before any call, try-once runs")
    void redissonExecutorThread() throws InterruptedException {
      Object waiting =
          onThread(
              "redisson-3-1",
              () -> operations.key("k").permits(5).waitTime(Duration.ofSeconds(1)).acquire());

      assertThat((Throwable) waiting)
          .isExactlyInstanceOf(IllegalStateException.class)
          .hasMessageStartingWith(
              "Locksmith cannot wait for a lock or permit on Redisson thread [redisson-3-1]");
      verifyNoInteractions(semaphore);

      Object once = onThread("redisson-3-1", () -> operations.key("k").permits(5).acquire());

      assertThat(((PermitHandle) once).acquired()).isTrue();
    }

    @Test
    @DisplayName("on the Redisson timer thread: even a try-once acquire throws before any call")
    void redissonTimerThread() throws InterruptedException {
      Object thrown =
          onThread("redisson-timer-4-1", () -> operations.key("k").permits(5).acquire());

      assertThat((Throwable) thrown)
          .isExactlyInstanceOf(IllegalStateException.class)
          .hasMessageStartingWith(
              "Locksmith cannot run on Redisson's timer thread [redisson-timer-4-1]");
      verifyNoInteractions(semaphore);
    }

    @Test
    @DisplayName(
        "on a Redis Cluster client, a key with a '{' that forms no hash tag throws before any call")
    void clusterKeyWithoutHashTag() {
      Config config = new Config();
      config.useClusterServers();
      when(redisson.getConfig()).thenReturn(config);

      assertThatThrownBy(() -> operations.key("order:x{1").permits(5).acquire())
          .isExactlyInstanceOf(LocksmithConfigurationException.class)
          .hasMessageStartingWith(
              "Key [test:semaphore:order:x{1] contains a '{' or '}' that forms no Redis Cluster"
                  + " hash tag");
      verify(redisson, never()).getPermitExpirableSemaphore(anyString());
    }

    @Test
    @DisplayName(
        "client shuts down while waiting: cancels the pending attempt, throws"
            + " RedissonShutdownException")
    void clientShutDownWhileWaiting() {
      CompletableFuture<List<String>> pending = new CompletableFuture<>();
      when(semaphore.tryAcquireAsync(anyInt(), anyLong(), anyLong(), any()))
          .thenReturn(new CompletableFutureWrapper<>(pending));
      when(redisson.isShuttingDown()).thenReturn(true);

      CompletableFuture<PermitHandle> attempt =
          CompletableFuture.supplyAsync(
              () -> operations.key("k").permits(5).waitTime(Duration.ofSeconds(30)).acquire());

      assertThatThrownBy(() -> attempt.get(5, SECONDS))
          .hasCauseInstanceOf(RedissonShutdownException.class);
      // Cancelling is what makes Redisson release a permit the attempt still wins.
      assertThat(pending).isCancelled();
    }

    @Test
    @DisplayName("propagates a Redisson RuntimeException from tryAcquireAsync unchanged")
    void redissonExceptionPropagates() throws InterruptedException {
      RedisConnectionException failure = new RedisConnectionException("Redis down");
      when(semaphore.tryAcquireAsync(anyInt(), anyLong(), anyLong(), any()))
          .thenReturn(new CompletableFutureWrapper<>(failure));

      assertThatThrownBy(() -> operations.key("k").permits(5).acquire()).isSameAs(failure);
    }

    @Test
    @DisplayName("a metrics failure after the permit was taken propagates and releases it once")
    void metricsFailureReleasesPermit() {
      IllegalArgumentException failure = new IllegalArgumentException("meter clash");
      doThrow(failure)
          .when(metrics)
          .recordAcquire(eq(Primitive.SEMAPHORE), eq(Outcome.ACQUIRED), any(Duration.class));

      assertThatThrownBy(() -> operations.key("k").permits(5).acquire()).isSameAs(failure);

      verify(semaphore, times(1)).releaseAsync("id-1");
    }
  }

  @Nested
  @DisplayName("ensurePermits")
  class EnsurePermits {

    @Test
    @DisplayName("create: getPermits 0 calls setPermits(n), which creates it atomically, no INFO")
    void create() {
      when(semaphore.getPermitsAsync()).thenReturn(done(0));

      operations.key("k").permits(3).acquire();

      verify(semaphore).setPermitsAsync(3);
      assertThat(infos()).isEmpty();
    }

    @Test
    @DisplayName("change: getPermits 2 with permits 5 calls setPermits(5) and logs one INFO")
    void change() {
      when(semaphore.getPermitsAsync()).thenReturn(done(2));

      operations.key("k").permits(5).acquire();

      verify(semaphore).setPermitsAsync(5);
      assertThat(infos()).hasSize(1);
      assertThat(infos().get(0).getFormattedMessage())
          .isEqualTo("Semaphore [" + FULL_KEY + "] permits changed from 2 to 5");
    }

    @Test
    @DisplayName("unchanged: getPermits 5 with permits 5 calls neither set method, no INFO")
    void unchanged() {
      operations.key("k").permits(5).acquire();

      verify(semaphore, never()).setPermitsAsync(anyInt());
      assertThat(infos()).isEmpty();
    }

    @Test
    @DisplayName("consults Redis once per key per value: a repeat is free, a new value asks again")
    void oncePerKeyPerValue() {
      operations.key("k").permits(5).acquire();
      operations.key("k").permits(5).acquire();

      verify(semaphore, times(1)).getPermitsAsync();

      operations.key("k").permits(7).acquire();

      verify(semaphore, times(2)).getPermitsAsync();
      verify(semaphore).setPermitsAsync(7);
    }

    @Test
    @DisplayName("a cached count is read without waiting on a count change in progress")
    void cachedCountDoesNotWaitOnUpdate() throws Exception {
      operations.key("k").permits(5).acquire();
      CountDownLatch inRedis = new CountDownLatch(1);
      CompletableFuture<Integer> count = new CompletableFuture<>();
      when(semaphore.getPermitsAsync())
          .thenAnswer(
              invocation -> {
                inRedis.countDown();
                return new CompletableFutureWrapper<>(count);
              });
      ExecutorService executor = Executors.newFixedThreadPool(2);
      try {
        Future<PermitHandle> change =
            executor.submit(() -> operations.key("k").permits(7).acquire());
        assertThat(inRedis.await(1, SECONDS)).isTrue();

        Future<PermitHandle> repeat =
            executor.submit(() -> operations.key("k").permits(5).acquire());

        assertThat(repeat.get(500, MILLISECONDS).acquired()).isTrue();
        count.complete(5);
        assertThat(change.get(1, SECONDS).acquired()).isTrue();
      } finally {
        count.complete(5);
        executor.shutdownNow();
      }
    }

    @Test
    @DisplayName("a Redis call for one key does not block a new key in the same map bin")
    void otherKeyInSameBinDoesNotWait() throws Exception {
      String keyA = "a";
      String keyB = keyInSameBin(keyA);
      RPermitExpirableSemaphore semaphoreA = mock(RPermitExpirableSemaphore.class);
      RPermitExpirableSemaphore semaphoreB = mock(RPermitExpirableSemaphore.class);
      when(redisson.getPermitExpirableSemaphore("test:semaphore:" + keyA)).thenReturn(semaphoreA);
      when(redisson.getPermitExpirableSemaphore("test:semaphore:" + keyB)).thenReturn(semaphoreB);
      CountDownLatch inRedis = new CountDownLatch(1);
      CompletableFuture<Integer> countA = new CompletableFuture<>();
      when(semaphoreA.getPermitsAsync())
          .thenAnswer(
              invocation -> {
                inRedis.countDown();
                return new CompletableFutureWrapper<>(countA);
              });
      when(semaphoreA.tryAcquireAsync(anyInt(), anyLong(), anyLong(), any()))
          .thenReturn(permit("id-a"));
      when(semaphoreB.getPermitsAsync()).thenReturn(done(5));
      when(semaphoreB.tryAcquireAsync(anyInt(), anyLong(), anyLong(), any()))
          .thenReturn(permit("id-b"));
      ExecutorService executor = Executors.newFixedThreadPool(2);
      try {
        Future<PermitHandle> first =
            executor.submit(() -> operations.key(keyA).permits(5).acquire());
        assertThat(inRedis.await(1, SECONDS)).isTrue();

        Future<PermitHandle> second =
            executor.submit(() -> operations.key(keyB).permits(5).acquire());

        assertThat(second.get(500, MILLISECONDS).permitId()).isEqualTo("id-b");
        countA.complete(5);
        assertThat(first.get(1, SECONDS).permitId()).isEqualTo("id-a");
      } finally {
        countA.complete(5);
        executor.shutdownNow();
      }
    }

    /**
     * Returns a key whose full key lands in the same bin as {@code key}'s in a ConcurrentHashMap of
     * the default 16 bins, the size of the still empty cache. The bin index is the spread hash of
     * ConcurrentHashMap masked to the table size.
     */
    private String keyInSameBin(String key) {
      int bin = bin("test:semaphore:" + key);
      for (int i = 0; ; i++) {
        String candidate = "b" + i;
        if (bin("test:semaphore:" + candidate) == bin) {
          return candidate;
        }
      }
    }

    private int bin(String fullKey) {
      int h = fullKey.hashCode();
      return (h ^ (h >>> 16)) & 15;
    }
  }

  @Nested
  @DisplayName("lost Redis state")
  class LostState {

    @Test
    @DisplayName("null permit id and getPermits 0: sets the count again and retries once")
    void reinitialisesAndRetries() throws InterruptedException {
      when(semaphore.getPermitsAsync()).thenReturn(done(0));
      when(semaphore.tryAcquireAsync(anyInt(), anyLong(), anyLong(), any()))
          .thenReturn(permit(null), permit("id-2"));

      PermitHandle handle = operations.key("k").permits(5).acquire();

      assertThat(handle.acquired()).isTrue();
      assertThat(handle.permitId()).isEqualTo("id-2");
      // once on the first acquire of the key, once after the loss
      verify(semaphore, times(2)).setPermitsAsync(5);
      verify(semaphore, times(2)).tryAcquireAsync(1, 0L, 90_000L, MILLISECONDS);
      assertThat(infos())
          .extracting(ILoggingEvent::getFormattedMessage)
          .containsExactly(
              "Semaphore [" + FULL_KEY + "] had no permits in Redis; count set to 5 again");
      verify(metrics)
          .recordAcquire(eq(Primitive.SEMAPHORE), eq(Outcome.ACQUIRED), any(Duration.class));
    }

    @Test
    @DisplayName("the retry waits only the part of the wait time that is left")
    void retryWaitsOnlyTimeLeft() throws InterruptedException {
      when(semaphore.getPermitsAsync()).thenReturn(done(0));
      when(semaphore.tryAcquireAsync(anyInt(), anyLong(), anyLong(), any()))
          .thenAnswer(
              invocation -> {
                Thread.sleep(50);
                return permit(null);
              })
          .thenReturn(permit("id-2"));

      PermitHandle handle =
          operations.key("k").permits(5).waitTime(Duration.ofMillis(1000)).acquire();

      assertThat(handle.acquired()).isTrue();
      ArgumentCaptor<Long> waits = ArgumentCaptor.forClass(Long.class);
      verify(semaphore, times(2))
          .tryAcquireAsync(eq(1), waits.capture(), eq(90_000L), eq(MILLISECONDS));
      assertThat(waits.getAllValues().get(0)).isEqualTo(1000L);
      assertThat(waits.getAllValues().get(1)).isBetween(0L, 950L);
    }

    @Test
    @DisplayName("null permit id and getPermits 2: the semaphore is full, no retry, SKIPPED")
    void fullSemaphoreIsNotReinitialised() throws InterruptedException {
      when(semaphore.getPermitsAsync()).thenReturn(done(2));
      when(semaphore.tryAcquireAsync(anyInt(), anyLong(), anyLong(), any()))
          .thenReturn(permit(null));

      PermitHandle handle = operations.key("k").permits(2).acquire();

      assertThat(handle.acquired()).isFalse();
      verify(semaphore, never()).setPermitsAsync(anyInt());
      verify(semaphore, times(1)).tryAcquireAsync(anyInt(), anyLong(), anyLong(), any());
      verify(metrics)
          .recordAcquire(eq(Primitive.SEMAPHORE), eq(Outcome.SKIPPED), any(Duration.class));
    }
  }

  @Nested
  @DisplayName("availablePermits")
  class AvailablePermits {

    @Test
    @DisplayName("returns availablePermits of the prefixed semaphore")
    void passThrough() {
      when(semaphore.availablePermits()).thenReturn(4);

      assertThat(operations.availablePermits("k")).isEqualTo(4);
      verify(redisson).getPermitExpirableSemaphore(FULL_KEY);
    }

    @Test
    @DisplayName("never below zero, where Redisson reports a lowered count still held as negative")
    void neverNegative() {
      when(semaphore.availablePermits()).thenReturn(-2);

      assertThat(operations.availablePermits("k")).isZero();
    }

    @Test
    @DisplayName(
        "on the Redisson timer thread throws before any call; on another Redisson thread it runs")
    void redissonThreads() throws InterruptedException {
      Object thrown = onThread("redisson-timer-4-1", () -> operations.availablePermits("k"));

      assertThat((Throwable) thrown)
          .isExactlyInstanceOf(IllegalStateException.class)
          .hasMessageStartingWith(
              "Locksmith cannot run on Redisson's timer thread [redisson-timer-4-1]");
      verifyNoInteractions(semaphore);

      when(semaphore.availablePermits()).thenReturn(4);

      assertThat(onThread("redisson-3-1", () -> operations.availablePermits("k"))).isEqualTo(4);
    }
  }
}
