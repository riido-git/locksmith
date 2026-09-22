package in.riido.locksmith.lock;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.riido.locksmith.LockType;
import in.riido.locksmith.autoconfigure.LocksmithProperties;
import in.riido.locksmith.metrics.LocksmithMetrics;
import in.riido.locksmith.metrics.LocksmithMetrics.Outcome;
import in.riido.locksmith.metrics.LocksmithMetrics.Primitive;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RReadWriteLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisConnectionException;

@DisplayName("LockOperations")
class LockOperationsTest {

  private static final String FULL_KEY = "test:lock:k";

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
    when(redisson.getReadWriteLock(FULL_KEY)).thenReturn(readWrite);
    when(readWrite.readLock()).thenReturn(readLock);
    when(readWrite.writeLock()).thenReturn(writeLock);
    metrics = mock(LocksmithMetrics.class);
    operations =
        new LockOperations(redisson, new LocksmithProperties(null, "test:", null), metrics);
  }

  @AfterEach
  void clearInterruptFlag() {
    Thread.interrupted();
  }

  @Nested
  @DisplayName("key layout")
  class KeyLayout {

    @Test
    @DisplayName("prefixes the key as <keyPrefix>lock:<key>")
    void prefixesKey() throws InterruptedException {
      when(reentrant.tryLock(anyLong(), anyLong(), any())).thenReturn(true);

      operations.key("k").acquire();

      verify(redisson).getLock(FULL_KEY);
    }

    @Test
    @DisplayName("uses the default prefix locksmith: when none is configured")
    void usesDefaultPrefix() throws InterruptedException {
      RLock lock = mock(RLock.class);
      when(redisson.getLock("locksmith:lock:k")).thenReturn(lock);
      when(lock.tryLock(anyLong(), anyLong(), any())).thenReturn(true);
      LockOperations defaults =
          new LockOperations(redisson, new LocksmithProperties(null, null, null), metrics);

      assertThat(defaults.key("k").acquire().acquired()).isTrue();
    }
  }

  @Nested
  @DisplayName("lock type")
  class Type {

    @Test
    @DisplayName("REENTRANT is the default and uses getLock")
    void reentrantByDefault() throws InterruptedException {
      when(reentrant.tryLock(anyLong(), anyLong(), any())).thenReturn(true);

      assertThat(operations.key("k").acquire().acquired()).isTrue();

      verify(reentrant).tryLock(0L, -1L, MILLISECONDS);
      verify(redisson, never()).getReadWriteLock(any(String.class));
    }

    @Test
    @DisplayName("READ uses the read lock of getReadWriteLock")
    void readUsesReadLock() throws InterruptedException {
      when(readLock.tryLock(anyLong(), anyLong(), any())).thenReturn(true);

      assertThat(operations.key("k").type(LockType.READ).acquire().acquired()).isTrue();

      verify(readLock).tryLock(0L, -1L, MILLISECONDS);
      verify(redisson, never()).getLock(any(String.class));
    }

    @Test
    @DisplayName("WRITE uses the write lock of getReadWriteLock")
    void writeUsesWriteLock() throws InterruptedException {
      when(writeLock.tryLock(anyLong(), anyLong(), any())).thenReturn(true);

      assertThat(operations.key("k").type(LockType.WRITE).acquire().acquired()).isTrue();

      verify(writeLock).tryLock(0L, -1L, MILLISECONDS);
      verify(redisson, never()).getLock(any(String.class));
    }
  }

  @Nested
  @DisplayName("durations passed to tryLock")
  class Durations {

    @Test
    @DisplayName("passes lease -1 when no leaseTime is set")
    void leaseMinusOneWithoutLeaseTime() throws InterruptedException {
      when(reentrant.tryLock(anyLong(), anyLong(), any())).thenReturn(true);

      operations.key("k").waitTime(Duration.ofSeconds(2)).acquire();

      verify(reentrant).tryLock(2000L, -1L, MILLISECONDS);
    }

    @Test
    @DisplayName("passes a fixed leaseTime through in milliseconds")
    void fixedLeaseInMillis() throws InterruptedException {
      when(reentrant.tryLock(anyLong(), anyLong(), any())).thenReturn(true);

      operations.key("k").leaseTime(Duration.ofMinutes(2)).acquire();

      verify(reentrant).tryLock(0L, 120_000L, MILLISECONDS);
    }

    @Test
    @DisplayName("passes waitTime in milliseconds")
    void waitInMillis() throws InterruptedException {
      when(reentrant.tryLock(anyLong(), anyLong(), any())).thenReturn(false);

      operations.key("k").waitTime(Duration.ofMillis(1500)).acquire();

      verify(reentrant).tryLock(1500L, -1L, MILLISECONDS);
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
    void acquired() throws InterruptedException {
      when(reentrant.tryLock(anyLong(), anyLong(), any())).thenReturn(true);

      LockHandle handle = operations.key("k").acquire();

      assertThat(handle.acquired()).isTrue();
      verify(metrics).recordAcquire(eq(Primitive.LOCK), eq(Outcome.ACQUIRED), any(Duration.class));
    }

    @Test
    @DisplayName("records outcome SKIPPED and returns an unacquired handle without throwing")
    void skipped() throws InterruptedException {
      when(reentrant.tryLock(anyLong(), anyLong(), any())).thenReturn(false);

      LockHandle handle = operations.key("k").acquire();

      assertThat(handle.acquired()).isFalse();
      verify(metrics).recordAcquire(eq(Primitive.LOCK), eq(Outcome.SKIPPED), any(Duration.class));
    }

    @Test
    @DisplayName(
        "on InterruptedException restores the interrupt flag, records INTERRUPTED, unacquired")
    void interrupted() throws InterruptedException {
      when(reentrant.tryLock(anyLong(), anyLong(), any())).thenThrow(new InterruptedException());

      LockHandle handle = operations.key("k").waitTime(Duration.ofSeconds(1)).acquire();

      assertThat(Thread.currentThread().isInterrupted()).isTrue();
      assertThat(handle.acquired()).isFalse();
      verify(metrics)
          .recordAcquire(eq(Primitive.LOCK), eq(Outcome.INTERRUPTED), any(Duration.class));
    }

    @Test
    @DisplayName("propagates a Redisson RuntimeException from tryLock unchanged")
    void redissonExceptionPropagates() throws InterruptedException {
      RedisConnectionException failure = new RedisConnectionException("Redis down");
      when(reentrant.tryLock(anyLong(), anyLong(), any())).thenThrow(failure);

      assertThatThrownBy(() -> operations.key("k").acquire()).isSameAs(failure);
    }

    @Test
    @DisplayName("a metrics failure after the lock was taken propagates and unlocks once")
    void metricsFailureReleasesLock() throws InterruptedException {
      when(reentrant.tryLock(anyLong(), anyLong(), any())).thenReturn(true);
      IllegalArgumentException failure = new IllegalArgumentException("meter clash");
      doThrow(failure)
          .when(metrics)
          .recordAcquire(eq(Primitive.LOCK), eq(Outcome.ACQUIRED), any(Duration.class));

      assertThatThrownBy(() -> operations.key("k").acquire()).isSameAs(failure);

      verify(reentrant, times(1)).unlock();
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
  }
}
