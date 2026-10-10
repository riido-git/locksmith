package in.riido.locksmith.lock;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

import in.riido.locksmith.LockType;
import in.riido.locksmith.LocksmithConfigurationException;
import in.riido.locksmith.autoconfigure.LocksmithProperties;
import in.riido.locksmith.metrics.LocksmithMetrics;
import in.riido.locksmith.metrics.LocksmithMetrics.Outcome;
import in.riido.locksmith.metrics.LocksmithMetrics.Primitive;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.redisson.RedissonShutdownException;
import org.redisson.api.RFuture;
import org.redisson.api.RLock;
import org.redisson.api.RReadWriteLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisConnectionException;
import org.redisson.config.Config;
import org.redisson.misc.CompletableFutureWrapper;

@DisplayName("LockOperations")
class LockOperationsTest {

  private static final String FULL_KEY = "test:lock:k";
  private static final String READ_WRITE_FULL_KEY = "test:rwlock:k";

  private RedissonClient redisson;
  private RLock reentrant;
  private RLock readLock;
  private RLock writeLock;
  private LocksmithMetrics metrics;
  private LockOperations operations;

  @BeforeEach
  void setUp() {
    redisson = mock(RedissonClient.class);
    reentrant = mock(RLock.class);
    readLock = mock(RLock.class);
    writeLock = mock(RLock.class);
    RReadWriteLock readWrite = mock(RReadWriteLock.class);
    when(redisson.getLock(FULL_KEY)).thenReturn(reentrant);
    when(redisson.getReadWriteLock(READ_WRITE_FULL_KEY)).thenReturn(readWrite);
    when(readWrite.readLock()).thenReturn(readLock);
    when(readWrite.writeLock()).thenReturn(writeLock);
    metrics = mock(LocksmithMetrics.class);
    operations =
        new LockOperations(redisson, new LocksmithProperties(null, "test:", null), metrics);
  }

  private static RFuture<Boolean> done(boolean acquired) {
    return new CompletableFutureWrapper<>(acquired);
  }

  /** An attempt that Redisson completes just before the interrupted caller's cancel reaches it. */
  private static RFuture<Boolean> completesBeforeCancel(boolean acquired) {
    CompletableFuture<Boolean> attempt = new CompletableFuture<>();
    return new CompletableFutureWrapper<>(attempt) {
      @Override
      public boolean cancel(boolean mayInterruptIfRunning) {
        attempt.complete(acquired);
        return super.cancel(mayInterruptIfRunning);
      }
    };
  }

  @AfterEach
  void clearInterruptFlag() {
    Thread.interrupted();
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
    @DisplayName("prefixes the key as <keyPrefix>lock:<key>")
    void prefixesKey() {
      when(reentrant.tryLockAsync(anyLong(), anyLong(), any(), anyLong())).thenReturn(done(true));

      operations.key("k").acquire();

      verify(redisson).getLock(FULL_KEY);
    }

    @Test
    @DisplayName("uses the default prefix locksmith: when none is configured")
    void usesDefaultPrefix() {
      RLock lock = mock(RLock.class);
      when(redisson.getLock("locksmith:lock:k")).thenReturn(lock);
      when(lock.tryLockAsync(anyLong(), anyLong(), any(), anyLong())).thenReturn(done(true));
      LockOperations defaults =
          new LockOperations(redisson, new LocksmithProperties(null, null, null), metrics);

      assertThat(defaults.key("k").acquire().acquired()).isTrue();
    }
  }

  @Nested
  @DisplayName("lock type")
  class Type {

    @Test
    @DisplayName("REENTRANT is the default, uses getLock, and is owned by the calling thread")
    void reentrantByDefault() {
      when(reentrant.tryLockAsync(anyLong(), anyLong(), any(), anyLong())).thenReturn(done(true));

      assertThat(operations.key("k").acquire().acquired()).isTrue();

      verify(reentrant).tryLockAsync(0L, -1L, MILLISECONDS, Thread.currentThread().getId());
      verify(redisson, never()).getReadWriteLock(any(String.class));
    }

    @Test
    @DisplayName("READ uses the read lock of getReadWriteLock")
    void readUsesReadLock() {
      when(readLock.tryLockAsync(anyLong(), anyLong(), any(), anyLong())).thenReturn(done(true));

      assertThat(operations.key("k").type(LockType.READ).acquire().acquired()).isTrue();

      verify(readLock).tryLockAsync(eq(0L), eq(-1L), eq(MILLISECONDS), anyLong());
      verify(redisson, never()).getLock(any(String.class));
    }

    @Test
    @DisplayName("WRITE uses the write lock of getReadWriteLock")
    void writeUsesWriteLock() {
      when(writeLock.tryLockAsync(anyLong(), anyLong(), any(), anyLong())).thenReturn(done(true));

      assertThat(operations.key("k").type(LockType.WRITE).acquire().acquired()).isTrue();

      verify(writeLock).tryLockAsync(eq(0L), eq(-1L), eq(MILLISECONDS), anyLong());
      verify(redisson, never()).getLock(any(String.class));
    }
  }

  @Nested
  @DisplayName("durations passed to tryLockAsync")
  class Durations {

    @Test
    @DisplayName("passes lease -1 when no leaseTime is set")
    void leaseMinusOneWithoutLeaseTime() {
      when(reentrant.tryLockAsync(anyLong(), anyLong(), any(), anyLong())).thenReturn(done(true));

      operations.key("k").waitTime(Duration.ofSeconds(2)).acquire();

      verify(reentrant).tryLockAsync(eq(2000L), eq(-1L), eq(MILLISECONDS), anyLong());
    }

    @Test
    @DisplayName("passes a fixed leaseTime through in milliseconds")
    void fixedLeaseInMillis() {
      when(reentrant.tryLockAsync(anyLong(), anyLong(), any(), anyLong())).thenReturn(done(true));

      operations.key("k").leaseTime(Duration.ofMinutes(2)).acquire();

      verify(reentrant).tryLockAsync(eq(0L), eq(120_000L), eq(MILLISECONDS), anyLong());
    }

    @Test
    @DisplayName("passes waitTime in milliseconds")
    void waitInMillis() {
      when(reentrant.tryLockAsync(anyLong(), anyLong(), any(), anyLong())).thenReturn(done(false));

      operations.key("k").waitTime(Duration.ofMillis(1500)).acquire();

      verify(reentrant).tryLockAsync(eq(1500L), eq(-1L), eq(MILLISECONDS), anyLong());
    }

    @Test
    @DisplayName("rejects a negative waitTime with IllegalArgumentException in the builder")
    void rejectsNegativeWait() {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> operations.key("k").waitTime(Duration.ofMillis(-1)))
          .withMessageContaining("waitTime");
      verify(redisson, never()).getLock(any(String.class));
    }

    @Test
    @DisplayName("rejects a negative leaseTime with IllegalArgumentException in the builder")
    void rejectsNegativeLease() {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> operations.key("k").leaseTime(Duration.ofMillis(-1)))
          .withMessageContaining("leaseTime");
      verify(redisson, never()).getLock(any(String.class));
    }

    @Test
    @DisplayName("rejects a zero leaseTime, which Redisson would treat as renewal")
    void rejectsZeroLease() {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> operations.key("k").leaseTime(Duration.ZERO))
          .withMessage("leaseTime must be at least one millisecond, got PT0S");
      verify(redisson, never()).getLock(any(String.class));
    }

    @Test
    @DisplayName("rejects a sub-millisecond leaseTime, which would round to zero")
    void rejectsSubMillisecondLease() {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> operations.key("k").leaseTime(Duration.ofNanos(500)))
          .withMessageContaining("leaseTime must be at least one millisecond");
      verify(redisson, never()).getLock(any(String.class));
    }
  }

  @Nested
  @DisplayName("outcome")
  class Outcomes {

    @Test
    @DisplayName("records outcome ACQUIRED and returns an acquired handle")
    void acquired() {
      when(reentrant.tryLockAsync(anyLong(), anyLong(), any(), anyLong())).thenReturn(done(true));

      LockHandle handle = operations.key("k").acquire();

      assertThat(handle.acquired()).isTrue();
      verify(metrics).recordAcquire(eq(Primitive.LOCK), eq(Outcome.ACQUIRED), any(Duration.class));
    }

    @Test
    @DisplayName("records outcome SKIPPED and returns an unacquired handle without throwing")
    void skipped() {
      when(reentrant.tryLockAsync(anyLong(), anyLong(), any(), anyLong())).thenReturn(done(false));

      LockHandle handle = operations.key("k").acquire();

      assertThat(handle.acquired()).isFalse();
      verify(metrics).recordAcquire(eq(Primitive.LOCK), eq(Outcome.SKIPPED), any(Duration.class));
    }

    @Test
    @DisplayName(
        "interrupted: cancels the pending attempt, restores the flag, records INTERRUPTED,"
            + " unacquired")
    void interrupted() {
      CompletableFuture<Boolean> pending = new CompletableFuture<>();
      when(reentrant.tryLockAsync(anyLong(), anyLong(), any(), anyLong()))
          .thenAnswer(interruptingAnd(new CompletableFutureWrapper<>(pending)));

      LockHandle handle = operations.key("k").waitTime(Duration.ofSeconds(1)).acquire();

      assertThat(Thread.currentThread().isInterrupted()).isTrue();
      assertThat(handle.acquired()).isFalse();
      // Cancelling is what makes Redisson release a lock the attempt still wins.
      assertThat(pending).isCancelled();
      verify(metrics)
          .recordAcquire(eq(Primitive.LOCK), eq(Outcome.INTERRUPTED), any(Duration.class));
    }

    @Test
    @DisplayName(
        "interrupted as the attempt wins: keeps the lock and the flag, records ACQUIRED, so no"
            + " lock is left without a handle")
    void interruptedAsAttemptWins() {
      when(reentrant.tryLockAsync(anyLong(), anyLong(), any(), anyLong()))
          .thenAnswer(interruptingAnd(completesBeforeCancel(true)));

      LockHandle handle = operations.key("k").waitTime(Duration.ofSeconds(1)).acquire();

      assertThat(Thread.currentThread().isInterrupted()).isTrue();
      assertThat(handle.acquired()).isTrue();
      verify(metrics).recordAcquire(eq(Primitive.LOCK), eq(Outcome.ACQUIRED), any(Duration.class));
    }

    @Test
    @DisplayName(
        "interrupted before acquire is called: unacquired, flag kept, records INTERRUPTED, and"
            + " nothing reaches Redisson")
    void interruptedBeforeTheCall() {
      Thread.currentThread().interrupt();

      LockHandle handle = operations.key("k").acquire();

      assertThat(Thread.currentThread().isInterrupted()).isTrue();
      assertThat(handle.acquired()).isFalse();
      verify(metrics)
          .recordAcquire(eq(Primitive.LOCK), eq(Outcome.INTERRUPTED), any(Duration.class));
      verify(reentrant, never()).tryLockAsync(anyLong(), anyLong(), any(), anyLong());
    }

    @Test
    @DisplayName(
        "on a Redisson executor thread: a waiting acquire throws before any call, try-once runs")
    void redissonExecutorThread() throws InterruptedException {
      when(reentrant.tryLockAsync(anyLong(), anyLong(), any(), anyLong())).thenReturn(done(true));

      Object waiting =
          onThread(
              "redisson-3-1", () -> operations.key("k").waitTime(Duration.ofSeconds(1)).acquire());

      assertThat((Throwable) waiting)
          .isExactlyInstanceOf(IllegalStateException.class)
          .hasMessageStartingWith(
              "Locksmith cannot wait for a lock or permit on Redisson thread [redisson-3-1]");
      verifyNoInteractions(reentrant);

      Object once = onThread("redisson-3-1", () -> operations.key("k").acquire());

      assertThat(((LockHandle) once).acquired()).isTrue();
    }

    @Test
    @DisplayName("on the Redisson timer thread: even a try-once acquire throws before any call")
    void redissonTimerThread() throws InterruptedException {
      Object thrown = onThread("redisson-timer-4-1", () -> operations.key("k").acquire());

      assertThat((Throwable) thrown)
          .isExactlyInstanceOf(IllegalStateException.class)
          .hasMessageStartingWith(
              "Locksmith cannot run on Redisson's timer thread [redisson-timer-4-1]");
      verifyNoInteractions(reentrant);
    }

    @Test
    @DisplayName(
        "on a Redis Cluster client, a key with a '{' that forms no hash tag throws before any call")
    void clusterKeyWithoutHashTag() {
      Config config = new Config();
      config.useClusterServers();
      when(redisson.getConfig()).thenReturn(config);

      assertThatThrownBy(() -> operations.key("order:x{1").acquire())
          .isExactlyInstanceOf(LocksmithConfigurationException.class)
          .hasMessageStartingWith(
              "Key [test:lock:order:x{1] contains a '{' or '}' that forms no Redis Cluster hash"
                  + " tag");
      verify(redisson, never()).getLock(anyString());
    }

    @Test
    @DisplayName(
        "client shuts down while waiting: cancels the pending attempt, throws"
            + " RedissonShutdownException")
    void clientShutDownWhileWaiting() {
      CompletableFuture<Boolean> pending = new CompletableFuture<>();
      when(reentrant.tryLockAsync(anyLong(), anyLong(), any(), anyLong()))
          .thenReturn(new CompletableFutureWrapper<>(pending));
      when(redisson.isShuttingDown()).thenReturn(true);

      CompletableFuture<LockHandle> attempt =
          CompletableFuture.supplyAsync(
              () -> operations.key("k").waitTime(Duration.ofSeconds(30)).acquire());

      assertThatThrownBy(() -> attempt.get(5, SECONDS))
          .hasCauseInstanceOf(RedissonShutdownException.class);
      // Cancelling is what makes Redisson release a lock the attempt still wins.
      assertThat(pending).isCancelled();
    }

    @Test
    @DisplayName("propagates a Redisson RuntimeException from tryLockAsync unchanged")
    void redissonExceptionPropagates() {
      RedisConnectionException failure = new RedisConnectionException("Redis down");
      when(reentrant.tryLockAsync(anyLong(), anyLong(), any(), anyLong()))
          .thenReturn(new CompletableFutureWrapper<>(failure));

      assertThatThrownBy(() -> operations.key("k").acquire()).isSameAs(failure);
    }

    @Test
    @DisplayName("a metrics failure after the lock was taken propagates and unlocks once")
    void metricsFailureReleasesLock() {
      when(reentrant.tryLockAsync(anyLong(), anyLong(), any(), anyLong())).thenReturn(done(true));
      when(reentrant.unlockAsync(anyLong()))
          .thenReturn(new CompletableFutureWrapper<>((Void) null));
      IllegalArgumentException failure = new IllegalArgumentException("meter clash");
      doThrow(failure)
          .when(metrics)
          .recordAcquire(eq(Primitive.LOCK), eq(Outcome.ACQUIRED), any(Duration.class));

      assertThatThrownBy(() -> operations.key("k").acquire()).isSameAs(failure);

      verify(reentrant, times(1)).unlockAsync(Thread.currentThread().getId());
    }
  }

  @Nested
  @DisplayName("isLocked")
  class IsLocked {

    @Test
    @DisplayName("returns isLocked of the prefixed REENTRANT lock")
    void reentrant() {
      when(reentrant.isLocked()).thenReturn(true);

      assertThat(operations.isLocked("k", LockType.REENTRANT)).isTrue();
    }

    @Test
    @DisplayName("returns isLocked of the read lock for READ")
    void read() {
      when(readLock.isLocked()).thenReturn(true);

      assertThat(operations.isLocked("k", LockType.READ)).isTrue();
    }

    @Test
    @DisplayName("returns isLocked of the write lock for WRITE")
    void write() {
      when(writeLock.isLocked()).thenReturn(false);

      assertThat(operations.isLocked("k", LockType.WRITE)).isFalse();
      verify(writeLock).isLocked();
    }

    @Test
    @DisplayName(
        "on the Redisson timer thread throws before any call; on another Redisson thread it runs")
    void redissonThreads() throws InterruptedException {
      Object thrown =
          onThread("redisson-timer-4-1", () -> operations.isLocked("k", LockType.REENTRANT));

      assertThat((Throwable) thrown)
          .isExactlyInstanceOf(IllegalStateException.class)
          .hasMessageStartingWith(
              "Locksmith cannot run on Redisson's timer thread [redisson-timer-4-1]");
      verifyNoInteractions(reentrant);

      when(reentrant.isLocked()).thenReturn(true);

      assertThat(onThread("redisson-3-1", () -> operations.isLocked("k", LockType.REENTRANT)))
          .isEqualTo(true);
    }
  }
}
