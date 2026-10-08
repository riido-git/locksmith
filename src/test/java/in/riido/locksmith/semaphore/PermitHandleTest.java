package in.riido.locksmith.semaphore;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
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
import org.redisson.api.RPermitExpirableSemaphore;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisConnectionException;
import org.redisson.misc.CompletableFutureWrapper;
import org.slf4j.LoggerFactory;

@DisplayName("PermitHandle")
class PermitHandleTest {

  private static final String FULL_KEY = "locksmith:semaphore:k";
  private static final String PERMIT_ID = "permit-1";

  private RedissonClient redisson;
  private RPermitExpirableSemaphore semaphore;
  private LocksmithMetrics metrics;
  private Logger logger;
  private ListAppender<ILoggingEvent> appender;

  @BeforeEach
  void setUp() {
    redisson = mock(RedissonClient.class);
    semaphore = mock(RPermitExpirableSemaphore.class);
    when(semaphore.releaseAsync(PERMIT_ID)).thenReturn(new CompletableFutureWrapper<>((Void) null));
    metrics = mock(LocksmithMetrics.class);
    logger = (Logger) LoggerFactory.getLogger(PermitHandle.class);
    appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void tearDown() {
    logger.detachAppender(appender);
    Thread.interrupted();
  }

  private PermitHandle acquiredHandle() {
    return new PermitHandle(
        redisson,
        semaphore,
        PERMIT_ID,
        FULL_KEY,
        Duration.ofSeconds(1),
        System.nanoTime(),
        Duration.ZERO,
        metrics);
  }

  private void releaseFailsWith(Throwable failure) {
    when(semaphore.releaseAsync(PERMIT_ID)).thenReturn(new CompletableFutureWrapper<>(failure));
  }

  private List<ILoggingEvent> warnings() {
    return appender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
  }

  @Test
  @DisplayName("key() returns the full key and permitId() the Redisson permit id")
  void accessors() {
    PermitHandle handle = acquiredHandle();

    assertThat(handle.key()).isEqualTo(FULL_KEY);
    assertThat(handle.permitId()).isEqualTo(PERMIT_ID);
    assertThat(handle.acquired()).isTrue();
  }

  @Nested
  @DisplayName("close on an acquired handle")
  class Acquired {

    @Test
    @DisplayName("releases the permit id and records locksmith.held for primitive SEMAPHORE")
    void releasesAndRecordsHeld() {
      acquiredHandle().close();

      verify(semaphore).releaseAsync(PERMIT_ID);
      verify(semaphore, never()).release(any(String.class));
      verify(metrics).recordHeld(eq(Primitive.SEMAPHORE), any(Duration.class));
      assertThat(warnings()).isEmpty();
    }

    @Test
    @DisplayName("is idempotent: a second close does not release again")
    void idempotent() {
      PermitHandle handle = acquiredHandle();

      handle.close();
      handle.close();

      verify(semaphore).releaseAsync(PERMIT_ID);
      verify(metrics).recordHeld(eq(Primitive.SEMAPHORE), any(Duration.class));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"redisson-netty-1-1", "redisson-timer-1-1"})
    @DisplayName(
        "on a Redisson I/O or timer thread it starts the release and returns without waiting")
    void doesNotWaitOnRedissonIoOrTimerThread(String threadName) throws Exception {
      CompletableFuture<Void> release = new CompletableFuture<>();
      when(semaphore.releaseAsync(PERMIT_ID)).thenReturn(new CompletableFutureWrapper<>(release));
      PermitHandle handle = acquiredHandle();
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
      verify(metrics, never()).recordHeld(any(), any());
      release.complete(null);
      verify(metrics).recordHeld(eq(Primitive.SEMAPHORE), any(Duration.class));
    }

    @Test
    @DisplayName("on a Redisson executor thread, such as a listener, it waits for the release")
    void waitsOnRedissonExecutorThread() throws Exception {
      CompletableFuture<Void> release = new CompletableFuture<>();
      when(semaphore.releaseAsync(PERMIT_ID)).thenReturn(new CompletableFutureWrapper<>(release));
      PermitHandle handle = acquiredHandle();
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
      verify(metrics).recordHeld(eq(Primitive.SEMAPHORE), any(Duration.class));
    }

    @Test
    @DisplayName("on an interrupted thread it releases, logs no WARN, and keeps the flag")
    void interruptedThread() {
      PermitHandle handle = acquiredHandle();
      Thread.currentThread().interrupt();

      handle.close();

      assertThat(Thread.currentThread().isInterrupted()).isTrue();
      verify(metrics).recordHeld(eq(Primitive.SEMAPHORE), any(Duration.class));
      assertThat(warnings()).isEmpty();
    }

    @Test
    @DisplayName("a recordHeld failure after release does not throw and logs one metrics WARN")
    void metricsFailureAfterRelease() {
      doThrow(new IllegalArgumentException("meter clash"))
          .when(metrics)
          .recordHeld(eq(Primitive.SEMAPHORE), any(Duration.class));
      PermitHandle handle = acquiredHandle();

      assertThatNoException().isThrownBy(handle::close);

      verify(semaphore).releaseAsync(PERMIT_ID);
      assertThat(warnings()).hasSize(1);
      assertThat(warnings().get(0).getFormattedMessage())
          .isEqualTo("Permit [" + FULL_KEY + "] metrics recording failed: meter clash");
    }
  }

  @Nested
  @DisplayName("client shutting down")
  class ClientShutdown {

    /** Closes on a new daemon thread, interrupted first; reports whether the flag survived. */
    private Thread closeInterrupted(PermitHandle handle, AtomicBoolean flagKept) {
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
      when(semaphore.releaseAsync(PERMIT_ID))
          .thenReturn(new CompletableFutureWrapper<>(new CompletableFuture<Void>()));
      when(redisson.isShuttingDown()).thenReturn(true);
      AtomicBoolean flagKept = new AtomicBoolean();

      Thread closer = closeInterrupted(acquiredHandle(), flagKept);
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
      when(semaphore.releaseAsync(PERMIT_ID)).thenReturn(new CompletableFutureWrapper<>(release));
      AtomicBoolean flagKept = new AtomicBoolean();

      Thread closer = closeInterrupted(acquiredHandle(), flagKept);

      verify(redisson, timeout(SECONDS.toMillis(5)).atLeastOnce()).isShuttingDown();
      assertThat(closer.isAlive()).as("close() still waiting").isTrue();
      release.complete(null);
      closer.join(SECONDS.toMillis(5));
      assertThat(closer.isAlive()).as("close() still waiting").isFalse();
      assertThat(flagKept).isTrue();
      verify(metrics).recordHeld(eq(Primitive.SEMAPHORE), any(Duration.class));
    }

    @Test
    @DisplayName("a release refused as Redisson shuts down logs one WARN line, without a trace")
    void refusedReleaseLogsOneQuietLine() {
      releaseFailsWith(new RedissonShutdownException("Redisson is shutdown"));

      assertThatNoException().isThrownBy(acquiredHandle()::close);

      assertThat(warnings()).hasSize(1);
      ILoggingEvent warning = warnings().get(0);
      assertThat(warning.getFormattedMessage())
          .startsWith("Permit [" + FULL_KEY + "] release was not confirmed after ")
          .endsWith(
              "ms (lease 1000ms) because the Redisson client is shutting down; the permit expires"
                  + " on its own");
      assertThat(warning.getThrowableProxy()).isNull();
      verify(metrics, never()).recordHeld(any(), any());
    }

    @Test
    @DisplayName(
        "a refused release wrapped twice, as on a Redis Cluster client, still logs the one quiet"
            + " line")
    void refusedReleaseWrappedTwiceLogsOneQuietLine() {
      releaseFailsWith(
          new CompletionException(
              new CompletionException(new RedissonShutdownException("Redisson is shutdown"))));

      acquiredHandle().close();

      assertThat(warnings()).hasSize(1);
      assertThat(warnings().get(0).getFormattedMessage())
          .startsWith("Permit [" + FULL_KEY + "] release was not confirmed after ");
      assertThat(warnings().get(0).getThrowableProxy()).isNull();
    }
  }

  @Nested
  @DisplayName("close on an unacquired handle")
  class Unacquired {

    @Test
    @DisplayName("reports acquired() false, permitId() null, and closes as a no-op")
    void noOp() {
      PermitHandle handle =
          new PermitHandle(
              redisson, null, null, FULL_KEY, Duration.ofSeconds(1), 0L, Duration.ZERO, metrics);

      assertThat(handle.acquired()).isFalse();
      assertThat(handle.permitId()).isNull();
      handle.close();

      verifyNoInteractions(metrics);
      assertThat(appender.list).isEmpty();
    }
  }

  @Nested
  @DisplayName("release failure")
  class ReleaseFailure {

    @Test
    @DisplayName(
        "an expired or unknown permit held short of its lease logs the not-held WARN, which still"
            + " names the lease as a possible cause, without a trace")
    void notHeldBeforeLeaseRanOut() {
      releaseFailsWith(
          new IllegalArgumentException(
              "Permit with id permit-1 has already been released or doesn't exist"));
      // acquiredHandle() takes acquiredAtNanos as now, so held is far short of the 1s lease.
      PermitHandle handle = acquiredHandle();

      assertThatNoException().isThrownBy(handle::close);

      assertThat(warnings()).hasSize(1);
      ILoggingEvent warning = warnings().get(0);
      assertThat(warning.getFormattedMessage())
          .startsWith("Permit [" + FULL_KEY + "] was reported as not held at release, ")
          .contains(
              "ms after its acquire returned (lease 1000ms). Possible causes: the lease ran out,"
                  + " counted from when the acquire was sent")
          .doesNotContain("another instance may have run concurrently")
          .endsWith("has already been released or doesn't exist");
      assertThat(warning.getThrowableProxy()).isNull();
      verify(metrics, never()).recordHeld(any(), any());
    }

    @Test
    @DisplayName(
        "an expired or unknown permit held its full lease logs the same not-held WARN, without a"
            + " trace")
    void notHeldAfterLeaseRanOut() {
      releaseFailsWith(
          new IllegalArgumentException(
              "Permit with id permit-1 has already been released or doesn't exist"));
      // acquiredAtNanos ten seconds ago with a one second lease makes held outrun the lease.
      PermitHandle handle =
          new PermitHandle(
              redisson,
              semaphore,
              PERMIT_ID,
              FULL_KEY,
              Duration.ofSeconds(1),
              System.nanoTime() - Duration.ofSeconds(10).toNanos(),
              Duration.ZERO,
              metrics);

      assertThatNoException().isThrownBy(handle::close);

      assertThat(warnings()).hasSize(1);
      ILoggingEvent warning = warnings().get(0);
      assertThat(warning.getFormattedMessage())
          .startsWith("Permit [" + FULL_KEY + "] was reported as not held at release, ")
          .contains("ms after its acquire returned (lease 1000ms). Possible causes: ")
          .endsWith("has already been released or doesn't exist");
      assertThat(warning.getThrowableProxy()).isNull();
      verify(metrics, never()).recordHeld(any(), any());
    }

    @Test
    @DisplayName("swallows any other failure and logs a WARN with the exception")
    void swallowsOtherFailure() {
      releaseFailsWith(new RedisConnectionException("Redis down"));
      PermitHandle handle = acquiredHandle();

      assertThatNoException().isThrownBy(handle::close);

      assertThat(warnings()).hasSize(1);
      ILoggingEvent warning = warnings().get(0);
      assertThat(warning.getFormattedMessage())
          .startsWith("Permit [" + FULL_KEY + "] release failed after ")
          .contains("(lease 1000ms)")
          .contains("Redis down");
      assertThat(warning.getThrowableProxy().getClassName())
          .isEqualTo(RedisConnectionException.class.getName());
    }
  }
}
