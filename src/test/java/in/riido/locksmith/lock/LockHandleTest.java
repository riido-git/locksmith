package in.riido.locksmith.lock;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
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
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.redisson.RedissonShutdownException;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisConnectionException;
import org.redisson.misc.CompletableFutureWrapper;
import org.slf4j.LoggerFactory;

@DisplayName("LockHandle")
class LockHandleTest {

  private static final String FULL_KEY = "locksmith:lock:k";
  private static final long OWNER = 7L;

  private RedissonClient redisson;
  private RLock lock;
  private LocksmithMetrics metrics;
  private Logger logger;
  private ListAppender<ILoggingEvent> appender;

  @BeforeEach
  void setUp() {
    redisson = mock(RedissonClient.class);
    lock = mock(RLock.class);
    when(lock.unlockAsync(anyLong())).thenReturn(new CompletableFutureWrapper<>((Void) null));
    metrics = mock(LocksmithMetrics.class);
    logger = (Logger) LoggerFactory.getLogger(LockHandle.class);
    appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void tearDown() {
    logger.detachAppender(appender);
    Thread.interrupted();
  }

  private LockHandle acquiredHandle(Duration fixedLease) {
    return new LockHandle(
        redisson, lock, OWNER, FULL_KEY, fixedLease, System.nanoTime(), Duration.ZERO, metrics);
  }

  private void releaseFailsWith(Throwable failure) {
    when(lock.unlockAsync(OWNER)).thenReturn(new CompletableFutureWrapper<>(failure));
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
    @DisplayName("unlocks as the owner and records locksmith.held for primitive LOCK")
    void unlocksAndRecordsHeld() {
      acquiredHandle(null).close();

      verify(lock).unlockAsync(OWNER);
      verify(lock, never()).unlock();
      verify(metrics).recordHeld(eq(Primitive.LOCK), any(Duration.class));
      assertThat(warnings()).isEmpty();
    }

    @Test
    @DisplayName("is idempotent: a second close does not unlock again")
    void idempotent() {
      LockHandle handle = acquiredHandle(null);

      handle.close();
      handle.close();

      verify(lock).unlockAsync(OWNER);
      verify(metrics).recordHeld(eq(Primitive.LOCK), any(Duration.class));
    }

    @Test
    @DisplayName("closed on another thread, it still unlocks as the owner")
    void closedOnAnotherThread() throws Exception {
      LockHandle handle = acquiredHandle(null);

      Thread other = new Thread(handle::close);
      other.start();
      other.join(SECONDS.toMillis(5));

      verify(lock).unlockAsync(OWNER);
      assertThat(warnings()).isEmpty();
    }

    @Test
    @DisplayName("waits until the release has finished before it returns")
    void waitsForRelease() {
      CompletableFuture<Void> release = new CompletableFuture<>();
      when(lock.unlockAsync(OWNER)).thenReturn(new CompletableFutureWrapper<>(release));
      CompletableFuture.delayedExecutor(200, MILLISECONDS).execute(() -> release.complete(null));

      long start = System.nanoTime();
      acquiredHandle(null).close();

      assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThan(Duration.ofMillis(150));
      verify(metrics).recordHeld(eq(Primitive.LOCK), any(Duration.class));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"redisson-netty-1-1", "redisson-timer-1-1"})
    @DisplayName(
        "on a Redisson I/O or timer thread it starts the release and returns without waiting")
    void doesNotWaitOnRedissonIoOrTimerThread(String threadName) throws Exception {
      CompletableFuture<Void> release = new CompletableFuture<>();
      when(lock.unlockAsync(OWNER)).thenReturn(new CompletableFutureWrapper<>(release));
      LockHandle handle = acquiredHandle(null);
      AtomicBoolean returned = new AtomicBoolean();

      Thread redisson =
          new Thread(
              () -> {
                handle.close();
                returned.set(true);
              },
              threadName);
      redisson.start();
      redisson.join(SECONDS.toMillis(5));

      assertThat(returned).isTrue();
      verify(lock).unlockAsync(OWNER);
      verify(metrics, never()).recordHeld(any(), any());
      release.complete(null);
      verify(metrics).recordHeld(eq(Primitive.LOCK), any(Duration.class));
    }

    @Test
    @DisplayName("on a Redisson executor thread, such as a listener, it waits for the release")
    void waitsOnRedissonExecutorThread() throws Exception {
      CompletableFuture<Void> release = new CompletableFuture<>();
      when(lock.unlockAsync(OWNER)).thenReturn(new CompletableFutureWrapper<>(release));
      LockHandle handle = acquiredHandle(null);
      AtomicBoolean returned = new AtomicBoolean();

      Thread listener =
          new Thread(
              () -> {
                handle.close();
                returned.set(true);
              },
              "redisson-3-1");
      listener.start();
      listener.join(200);

      assertThat(returned).isFalse();
      release.complete(null);
      listener.join(SECONDS.toMillis(5));
      assertThat(returned).isTrue();
      verify(metrics).recordHeld(eq(Primitive.LOCK), any(Duration.class));
    }

    @Test
    @DisplayName("on an interrupted thread it releases, logs no WARN, and keeps the flag")
    void interruptedThread() {
      LockHandle handle = acquiredHandle(null);
      Thread.currentThread().interrupt();

      handle.close();

      assertThat(Thread.currentThread().isInterrupted()).isTrue();
      verify(lock).unlockAsync(OWNER);
      verify(metrics).recordHeld(eq(Primitive.LOCK), any(Duration.class));
      assertThat(warnings()).isEmpty();
    }

    @Test
    @DisplayName("a recordHeld failure after unlock does not throw and logs one metrics WARN")
    void metricsFailureAfterUnlock() {
      doThrow(new IllegalArgumentException("meter clash"))
          .when(metrics)
          .recordHeld(eq(Primitive.LOCK), any(Duration.class));
      LockHandle handle = acquiredHandle(null);

      assertThatNoException().isThrownBy(handle::close);

      verify(lock).unlockAsync(OWNER);
      assertThat(warnings()).hasSize(1);
      assertThat(warnings().get(0).getFormattedMessage())
          .isEqualTo("Lock [" + FULL_KEY + "] metrics recording failed: meter clash");
    }
  }

  @Nested
  @DisplayName("client shutting down")
  class ClientShutdown {

    /** Closes on a new daemon thread, interrupted first; reports whether the flag survived. */
    private Thread closeInterrupted(LockHandle handle, AtomicBoolean flagKept) {
      Thread closer =
          new Thread(
              () -> {
                Thread.currentThread().interrupt();
                handle.close();
                flagKept.set(Thread.currentThread().isInterrupted());
              });
      // A close() that never returns must not keep the test JVM alive.
      closer.setDaemon(true);
      closer.start();
      return closer;
    }

    @Test
    @DisplayName(
        "a release that never finishes: close() stops waiting within about two seconds, flag kept")
    void stopsWaitingOnceShuttingDown() throws Exception {
      when(lock.unlockAsync(OWNER))
          .thenReturn(new CompletableFutureWrapper<>(new CompletableFuture<Void>()));
      when(redisson.isShuttingDown()).thenReturn(true);
      AtomicBoolean flagKept = new AtomicBoolean();

      Thread closer = closeInterrupted(acquiredHandle(null), flagKept);
      closer.join(2500);

      // Before the fix, close() waited in join() for good, interrupted or not.
      assertThat(closer.isAlive()).as("close() still waiting").isFalse();
      assertThat(flagKept).isTrue();
      verify(metrics, never()).recordHeld(any(), any());
    }

    @Test
    @DisplayName(
        "a client that is not shutting down: close() goes on waiting past a check, flag kept")
    void waitsWhileClientRuns() throws Exception {
      CompletableFuture<Void> release = new CompletableFuture<>();
      when(lock.unlockAsync(OWNER)).thenReturn(new CompletableFutureWrapper<>(release));
      AtomicBoolean flagKept = new AtomicBoolean();

      Thread closer = closeInterrupted(acquiredHandle(null), flagKept);

      verify(redisson, timeout(SECONDS.toMillis(5)).atLeastOnce()).isShuttingDown();
      assertThat(closer.isAlive()).as("close() still waiting").isTrue();
      release.complete(null);
      closer.join(SECONDS.toMillis(5));
      assertThat(closer.isAlive()).as("close() still waiting").isFalse();
      assertThat(flagKept).isTrue();
      verify(metrics).recordHeld(eq(Primitive.LOCK), any(Duration.class));
    }

    @Test
    @DisplayName("a release refused as Redisson shuts down logs one WARN line, without a trace")
    void refusedReleaseLogsOneQuietLine() {
      releaseFailsWith(new RedissonShutdownException("Redisson is shutdown"));

      assertThatNoException().isThrownBy(acquiredHandle(null)::close);

      assertThat(warnings()).hasSize(1);
      ILoggingEvent warning = warnings().get(0);
      assertThat(warning.getFormattedMessage())
          .startsWith("Lock [" + FULL_KEY + "] release was not confirmed after ")
          .endsWith(
              "ms (fixed lease none) because the Redisson client is shutting down; the key"
                  + " expires on its own");
      assertThat(warning.getThrowableProxy()).isNull();
      verify(metrics, never()).recordHeld(any(), any());
    }

    @Test
    @DisplayName("a refused release wrapped twice still logs the one quiet line")
    void refusedReleaseWrappedTwiceLogsOneQuietLine() {
      releaseFailsWith(
          new CompletionException(
              new CompletionException(new RedissonShutdownException("Redisson is shutdown"))));

      acquiredHandle(null).close();

      assertThat(warnings()).hasSize(1);
      assertThat(warnings().get(0).getFormattedMessage())
          .startsWith("Lock [" + FULL_KEY + "] release was not confirmed after ");
      assertThat(warnings().get(0).getThrowableProxy()).isNull();
    }
  }

  @Nested
  @DisplayName("close on an unacquired handle")
  class Unacquired {

    @Test
    @DisplayName("reports acquired() false and closes as a no-op without metric")
    void noOp() {
      LockHandle handle =
          new LockHandle(redisson, null, 0L, FULL_KEY, null, 0L, Duration.ZERO, metrics);

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
      releaseFailsWith(new IllegalMonitorStateException("not locked by current thread"));
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
      releaseFailsWith(new IllegalMonitorStateException("gone"));

      acquiredHandle(null).close();

      assertThat(warnings().get(0).getFormattedMessage()).contains("(fixed lease none)");
    }

    @Test
    @DisplayName("swallows any other failure and logs a WARN with the exception")
    void swallowsOtherFailure() {
      releaseFailsWith(new RedisConnectionException("Redis down"));
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

    @Test
    @DisplayName("an exception thrown by the release call itself is swallowed and logged too")
    void swallowsThrownException() {
      RedisConnectionException failure = new RedisConnectionException("Redis down");
      doThrow(failure).when(lock).unlockAsync(OWNER);

      assertThatNoException().isThrownBy(acquiredHandle(null)::close);

      assertThat(warnings()).hasSize(1);
      assertThat(warnings().get(0).getFormattedMessage()).contains("release failed");
    }
  }
}
