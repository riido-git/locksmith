package in.riido.locksmith.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import in.riido.locksmith.metrics.LocksmithMetrics;
import in.riido.locksmith.metrics.LocksmithMetrics.Primitive;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.client.RedisConnectionException;
import org.redisson.misc.CompletableFutureWrapper;
import org.slf4j.LoggerFactory;

@DisplayName("LockHandle")
class LockHandleTest {

  private static final String FULL_KEY = "locksmith:lock:k";

  private RLock lock;
  private LocksmithMetrics metrics;
  private Logger logger;
  private ListAppender<ILoggingEvent> appender;

  @BeforeEach
  void setUp() {
    lock = mock(RLock.class);
    metrics = mock(LocksmithMetrics.class);
    logger = (Logger) LoggerFactory.getLogger(LockHandle.class);
    appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void tearDown() {
    logger.detachAppender(appender);
  }

  private LockHandle acquiredHandle(Duration fixedLease) {
    return new LockHandle(lock, FULL_KEY, fixedLease, System.nanoTime(), Duration.ZERO, metrics);
  }

  private List<ILoggingEvent> warnings() {
    return appender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
  }

  @Test
  @DisplayName("key() returns the full key including the prefix")
  void keyReturnsFullKey() {
    assertThat(acquiredHandle(null).key()).isEqualTo(FULL_KEY);
  }

  @Nested
  @DisplayName("close on an acquired handle")
  class Acquired {

    @Test
    @DisplayName("unlocks and records locksmith.held for primitive LOCK")
    void unlocksAndRecordsHeld() {
      acquiredHandle(null).close();

      verify(lock).unlock();
      verify(metrics).recordHeld(eq(Primitive.LOCK), any(Duration.class));
      assertThat(warnings()).isEmpty();
    }

    @Test
    @DisplayName("is idempotent: a second close does not unlock again")
    void idempotent() {
      LockHandle handle = acquiredHandle(null);

      handle.close();
      handle.close();

      verify(lock).unlock();
      verify(metrics).recordHeld(eq(Primitive.LOCK), any(Duration.class));
    }

    @Test
    @DisplayName("a recordHeld failure after unlock does not throw and logs one metrics WARN")
    void metricsFailureAfterUnlock() {
      doThrow(new IllegalArgumentException("meter clash"))
          .when(metrics)
          .recordHeld(eq(Primitive.LOCK), any(Duration.class));
      LockHandle handle = acquiredHandle(null);

      assertThatNoException().isThrownBy(handle::close);

      verify(lock).unlock();
      assertThat(warnings()).hasSize(1);
      assertThat(warnings().get(0).getFormattedMessage())
          .isEqualTo("Lock [" + FULL_KEY + "] metrics recording failed: meter clash");
    }
  }

  @Nested
  @DisplayName("close on an unacquired handle")
  class Unacquired {

    @Test
    @DisplayName("reports acquired() false and closes as a no-op without metric")
    void noOp() {
      LockHandle handle = new LockHandle(null, FULL_KEY, null, 0L, Duration.ZERO, metrics);

      assertThat(handle.acquired()).isFalse();
      handle.close();

      verifyNoInteractions(metrics);
      assertThat(appender.list).isEmpty();
    }
  }

  @Nested
  @DisplayName("release failure")
  class ReleaseFailure {

    @Test
    @DisplayName("swallows IllegalMonitorStateException and logs the no-longer-held WARN")
    void swallowsIllegalMonitorState() {
      doThrow(new IllegalMonitorStateException("not locked by current thread")).when(lock).unlock();
      LockHandle handle = acquiredHandle(Duration.ofSeconds(1));

      assertThatNoException().isThrownBy(handle::close);

      assertThat(warnings()).hasSize(1);
      String message = warnings().get(0).getFormattedMessage();
      assertThat(message)
          .startsWith("Lock [" + FULL_KEY + "] was no longer held at release after ")
          .contains("(fixed lease 1000ms); another instance may have run concurrently")
          .contains("not locked by current thread");
      verify(metrics, never()).recordHeld(any(), any());
    }

    @Test
    @DisplayName("says 'fixed lease none' in the WARN when renewal was on")
    void noFixedLease() {
      doThrow(new IllegalMonitorStateException("gone")).when(lock).unlock();

      acquiredHandle(null).close();

      assertThat(warnings().get(0).getFormattedMessage()).contains("(fixed lease none)");
    }

    @Test
    @DisplayName("swallows any other RuntimeException and logs a WARN with the exception")
    void swallowsOtherRuntimeException() {
      RedisConnectionException failure = new RedisConnectionException("Redis down");
      doThrow(failure).when(lock).unlock();
      LockHandle handle = acquiredHandle(null);

      assertThatNoException().isThrownBy(handle::close);

      assertThat(warnings()).hasSize(1);
      ILoggingEvent warning = warnings().get(0);
      assertThat(warning.getFormattedMessage())
          .startsWith("Lock [" + FULL_KEY + "] release failed after ")
          .contains("Redis down");
      assertThat(warning.getThrowableProxy().getClassName())
          .isEqualTo(RedisConnectionException.class.getName());
    }
  }

  @Nested
  @DisplayName("async release of a handle owned by an id")
  class OwnedRelease {

    private static final long OWNER = -7L;

    private LockHandle ownedHandle() {
      return new LockHandle(lock, OWNER, FULL_KEY, null, System.nanoTime(), Duration.ZERO, metrics);
    }

    @Test
    @DisplayName("unlocks with the owner id, not the thread, and records locksmith.held")
    void unlocksWithOwnerId() {
      when(lock.unlockAsync(OWNER)).thenReturn(new CompletableFutureWrapper<>((Void) null));

      CompletionStage<Void> released = ownedHandle().releaseAsync();

      assertThat(released.toCompletableFuture()).isCompletedWithValue(null);
      verify(lock).unlockAsync(OWNER);
      verify(lock, never()).unlock();
      verify(metrics).recordHeld(eq(Primitive.LOCK), any(Duration.class));
      assertThat(warnings()).isEmpty();
    }

    @Test
    @DisplayName("close() on an owned handle releases with the owner id and waits for it")
    void closeUsesOwnerId() {
      when(lock.unlockAsync(OWNER)).thenReturn(new CompletableFutureWrapper<>((Void) null));
      LockHandle handle = ownedHandle();

      handle.close();
      handle.close();

      verify(lock).unlockAsync(OWNER);
      verify(lock, never()).unlock();
    }

    @Test
    @DisplayName("is idempotent: a second release does not unlock again")
    void idempotent() {
      when(lock.unlockAsync(OWNER)).thenReturn(new CompletableFutureWrapper<>((Void) null));
      LockHandle handle = ownedHandle();

      handle.releaseAsync();
      handle.releaseAsync();

      verify(lock).unlockAsync(OWNER);
    }

    @Test
    @DisplayName(
        "a no-longer-held failure logs the same WARN and the stage still completes normally")
    void notHeld() {
      when(lock.unlockAsync(OWNER))
          .thenReturn(new CompletableFutureWrapper<>(new IllegalMonitorStateException("gone")));

      CompletableFuture<Void> released = ownedHandle().releaseAsync().toCompletableFuture();

      assertThat(released).isCompletedWithValue(null);
      assertThat(warnings()).hasSize(1);
      assertThat(warnings().get(0).getFormattedMessage())
          .startsWith("Lock [" + FULL_KEY + "] was no longer held at release after ")
          .contains("(fixed lease none); another instance may have run concurrently: gone");
      verify(metrics, never()).recordHeld(any(), any());
    }

    @Test
    @DisplayName("any other failure logs the release-failed WARN with the exception")
    void otherFailure() {
      when(lock.unlockAsync(OWNER))
          .thenReturn(new CompletableFutureWrapper<>(new RedisConnectionException("Redis down")));

      CompletableFuture<Void> released = ownedHandle().releaseAsync().toCompletableFuture();

      assertThat(released).isCompletedWithValue(null);
      ILoggingEvent warning = warnings().get(0);
      assertThat(warning.getFormattedMessage())
          .startsWith("Lock [" + FULL_KEY + "] release failed after ")
          .contains("Redis down");
      assertThat(warning.getThrowableProxy().getClassName())
          .isEqualTo(RedisConnectionException.class.getName());
    }

    @Test
    @DisplayName("an unacquired handle completes at once without touching Redis")
    void unacquired() {
      LockHandle handle = new LockHandle(null, OWNER, FULL_KEY, null, 0L, Duration.ZERO, metrics);

      assertThat(handle.releaseAsync().toCompletableFuture()).isCompletedWithValue(null);
      verifyNoInteractions(lock, metrics);
    }
  }
}
