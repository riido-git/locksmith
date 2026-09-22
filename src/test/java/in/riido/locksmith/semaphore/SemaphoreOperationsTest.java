package in.riido.locksmith.semaphore;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RPermitExpirableSemaphore;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisConnectionException;
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
    when(semaphore.getPermits()).thenReturn(5);
    when(semaphore.trySetPermits(anyInt())).thenReturn(true);
    when(semaphore.tryAcquire(anyLong(), anyLong(), any())).thenReturn("id-1");
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

  private List<ILoggingEvent> infos() {
    return appender.list.stream().filter(e -> e.getLevel() == Level.INFO).toList();
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
  @DisplayName("durations passed to tryAcquire")
  class Durations {

    @Test
    @DisplayName("defaults: wait 0 and the lease from locksmith.semaphore.lease-time, in ms")
    void defaults() throws InterruptedException {
      operations.key("k").permits(5).acquire();

      verify(semaphore).tryAcquire(0L, 90_000L, MILLISECONDS);
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

      verify(semaphore).tryAcquire(1500L, 30_000L, MILLISECONDS);
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
      when(semaphore.tryAcquire(anyLong(), anyLong(), any())).thenReturn(null);

      PermitHandle handle = operations.key("k").permits(5).acquire();

      assertThat(handle.acquired()).isFalse();
      assertThat(handle.permitId()).isNull();
      verify(metrics)
          .recordAcquire(eq(Primitive.SEMAPHORE), eq(Outcome.SKIPPED), any(Duration.class));
    }

    @Test
    @DisplayName(
        "on InterruptedException restores the interrupt flag, records INTERRUPTED, unacquired")
    void interrupted() throws InterruptedException {
      when(semaphore.tryAcquire(anyLong(), anyLong(), any())).thenThrow(new InterruptedException());

      PermitHandle handle = operations.key("k").permits(5).acquire();

      assertThat(Thread.currentThread().isInterrupted()).isTrue();
      assertThat(handle.acquired()).isFalse();
      verify(metrics)
          .recordAcquire(eq(Primitive.SEMAPHORE), eq(Outcome.INTERRUPTED), any(Duration.class));
    }

    @Test
    @DisplayName("propagates a Redisson RuntimeException from tryAcquire unchanged")
    void redissonExceptionPropagates() throws InterruptedException {
      RedisConnectionException failure = new RedisConnectionException("Redis down");
      when(semaphore.tryAcquire(anyLong(), anyLong(), any())).thenThrow(failure);

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

      verify(semaphore, times(1)).release("id-1");
    }
  }

  @Nested
  @DisplayName("ensurePermits")
  class EnsurePermits {

    @Test
    @DisplayName("create: getPermits 0 calls trySetPermits(n), not setPermits, no INFO")
    void create() {
      when(semaphore.getPermits()).thenReturn(0);

      operations.key("k").permits(3).acquire();

      verify(semaphore).trySetPermits(3);
      verify(semaphore, never()).setPermits(anyInt());
      assertThat(infos()).isEmpty();
    }

    @Test
    @DisplayName("change: getPermits 2 with permits 5 calls setPermits(5) and logs one INFO")
    void change() {
      when(semaphore.getPermits()).thenReturn(2);

      operations.key("k").permits(5).acquire();

      verify(semaphore).setPermits(5);
      verify(semaphore, never()).trySetPermits(anyInt());
      assertThat(infos()).hasSize(1);
      assertThat(infos().get(0).getFormattedMessage())
          .isEqualTo("Semaphore [" + FULL_KEY + "] permits changed from 2 to 5");
    }

    @Test
    @DisplayName("unchanged: getPermits 5 with permits 5 calls neither set method, no INFO")
    void unchanged() {
      operations.key("k").permits(5).acquire();

      verify(semaphore, never()).trySetPermits(anyInt());
      verify(semaphore, never()).setPermits(anyInt());
      assertThat(infos()).isEmpty();
    }

    @Test
    @DisplayName("create lost to another instance: re-reads the count and changes it, one INFO")
    void createLostChangesCount() {
      when(semaphore.getPermits()).thenReturn(0, 3);
      when(semaphore.trySetPermits(5)).thenReturn(false);

      operations.key("k").permits(5).acquire();

      verify(semaphore).setPermits(5);
      assertThat(infos()).hasSize(1);
      assertThat(infos().get(0).getFormattedMessage())
          .isEqualTo("Semaphore [" + FULL_KEY + "] permits changed from 3 to 5");
    }

    @Test
    @DisplayName("create lost to another instance with the same count: no setPermits, no INFO")
    void createLostSameCount() {
      when(semaphore.getPermits()).thenReturn(0, 5);
      when(semaphore.trySetPermits(5)).thenReturn(false);

      operations.key("k").permits(5).acquire();

      verify(semaphore, times(2)).getPermits();
      verify(semaphore, never()).setPermits(anyInt());
      assertThat(infos()).isEmpty();
    }

    @Test
    @DisplayName("consults Redis once per key per value: a repeat is free, a new value asks again")
    void oncePerKeyPerValue() {
      operations.key("k").permits(5).acquire();
      operations.key("k").permits(5).acquire();

      verify(semaphore, times(1)).getPermits();

      operations.key("k").permits(7).acquire();

      verify(semaphore, times(2)).getPermits();
      verify(semaphore).setPermits(7);
    }

    @Test
    @DisplayName("a cached count is read without waiting on a count change in progress")
    void cachedCountDoesNotWaitOnUpdate() throws Exception {
      operations.key("k").permits(5).acquire();
      CountDownLatch inRedis = new CountDownLatch(1);
      CountDownLatch finish = new CountDownLatch(1);
      when(semaphore.getPermits())
          .thenAnswer(
              invocation -> {
                inRedis.countDown();
                finish.await();
                return 5;
              });
      ExecutorService executor = Executors.newFixedThreadPool(2);
      try {
        Future<PermitHandle> change =
            executor.submit(() -> operations.key("k").permits(7).acquire());
        assertThat(inRedis.await(1, SECONDS)).isTrue();

        Future<PermitHandle> repeat =
            executor.submit(() -> operations.key("k").permits(5).acquire());

        assertThat(repeat.get(500, MILLISECONDS).acquired()).isTrue();
        finish.countDown();
        assertThat(change.get(1, SECONDS).acquired()).isTrue();
      } finally {
        finish.countDown();
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
      CountDownLatch finish = new CountDownLatch(1);
      when(semaphoreA.getPermits())
          .thenAnswer(
              invocation -> {
                inRedis.countDown();
                finish.await();
                return 5;
              });
      when(semaphoreA.tryAcquire(anyLong(), anyLong(), any())).thenReturn("id-a");
      when(semaphoreB.getPermits()).thenReturn(5);
      when(semaphoreB.tryAcquire(anyLong(), anyLong(), any())).thenReturn("id-b");
      ExecutorService executor = Executors.newFixedThreadPool(2);
      try {
        Future<PermitHandle> first =
            executor.submit(() -> operations.key(keyA).permits(5).acquire());
        assertThat(inRedis.await(1, SECONDS)).isTrue();

        Future<PermitHandle> second =
            executor.submit(() -> operations.key(keyB).permits(5).acquire());

        assertThat(second.get(500, MILLISECONDS).permitId()).isEqualTo("id-b");
        finish.countDown();
        assertThat(first.get(1, SECONDS).permitId()).isEqualTo("id-a");
      } finally {
        finish.countDown();
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
      when(semaphore.getPermits()).thenReturn(0);
      when(semaphore.tryAcquire(anyLong(), anyLong(), any())).thenReturn(null, "id-2");

      PermitHandle handle = operations.key("k").permits(5).acquire();

      assertThat(handle.acquired()).isTrue();
      assertThat(handle.permitId()).isEqualTo("id-2");
      verify(semaphore, times(2)).trySetPermits(5);
      verify(semaphore, times(2)).tryAcquire(0L, 90_000L, MILLISECONDS);
      verify(metrics)
          .recordAcquire(eq(Primitive.SEMAPHORE), eq(Outcome.ACQUIRED), any(Duration.class));
    }

    @Test
    @DisplayName("the retry waits only the part of the wait time that is left")
    void retryWaitsOnlyTimeLeft() throws InterruptedException {
      when(semaphore.getPermits()).thenReturn(0);
      when(semaphore.tryAcquire(anyLong(), anyLong(), any()))
          .thenAnswer(
              invocation -> {
                Thread.sleep(50);
                return null;
              })
          .thenReturn("id-2");

      PermitHandle handle =
          operations.key("k").permits(5).waitTime(Duration.ofMillis(1000)).acquire();

      assertThat(handle.acquired()).isTrue();
      ArgumentCaptor<Long> waits = ArgumentCaptor.forClass(Long.class);
      verify(semaphore, times(2)).tryAcquire(waits.capture(), eq(90_000L), eq(MILLISECONDS));
      assertThat(waits.getAllValues().get(0)).isEqualTo(1000L);
      assertThat(waits.getAllValues().get(1)).isBetween(0L, 950L);
    }

    @Test
    @DisplayName("null permit id and getPermits 2: the semaphore is full, no retry, SKIPPED")
    void fullSemaphoreIsNotReinitialised() throws InterruptedException {
      when(semaphore.getPermits()).thenReturn(2);
      when(semaphore.tryAcquire(anyLong(), anyLong(), any())).thenReturn(null);

      PermitHandle handle = operations.key("k").permits(2).acquire();

      assertThat(handle.acquired()).isFalse();
      verify(semaphore, never()).trySetPermits(anyInt());
      verify(semaphore, never()).setPermits(anyInt());
      verify(semaphore, times(1)).tryAcquire(anyLong(), anyLong(), any());
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
  }
}
