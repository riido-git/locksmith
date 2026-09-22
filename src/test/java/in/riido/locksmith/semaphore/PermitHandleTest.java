package in.riido.locksmith.semaphore;

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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.redisson.api.RPermitExpirableSemaphore;
import org.redisson.client.RedisConnectionException;
import org.redisson.client.RedisException;
import org.redisson.misc.CompletableFutureWrapper;
import org.slf4j.LoggerFactory;

@DisplayName("PermitHandle")
class PermitHandleTest {

  private static final String FULL_KEY = "locksmith:semaphore:k";
  private static final String PERMIT_ID = "permit-1";

  private RPermitExpirableSemaphore semaphore;
  private LocksmithMetrics metrics;
  private Logger logger;
  private ListAppender<ILoggingEvent> appender;

  @BeforeEach
  void setUp() {
    semaphore = mock(RPermitExpirableSemaphore.class);
    metrics = mock(LocksmithMetrics.class);
    logger = (Logger) LoggerFactory.getLogger(PermitHandle.class);
    appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void tearDown() {
    logger.detachAppender(appender);
  }

  private PermitHandle acquiredHandle() {
    return new PermitHandle(
        semaphore,
        PERMIT_ID,
        FULL_KEY,
        Duration.ofSeconds(1),
        System.nanoTime(),
        Duration.ZERO,
        metrics);
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

      verify(semaphore).release(PERMIT_ID);
      verify(metrics).recordHeld(eq(Primitive.SEMAPHORE), any(Duration.class));
      assertThat(warnings()).isEmpty();
    }

    @Test
    @DisplayName("is idempotent: a second close does not release again")
    void idempotent() {
      PermitHandle handle = acquiredHandle();

      handle.close();
      handle.close();

      verify(semaphore).release(PERMIT_ID);
      verify(metrics).recordHeld(eq(Primitive.SEMAPHORE), any(Duration.class));
    }

    @Test
    @DisplayName("a recordHeld failure after release does not throw and logs one metrics WARN")
    void metricsFailureAfterRelease() {
      doThrow(new IllegalArgumentException("meter clash"))
          .when(metrics)
          .recordHeld(eq(Primitive.SEMAPHORE), any(Duration.class));
      PermitHandle handle = acquiredHandle();

      assertThatNoException().isThrownBy(handle::close);

      verify(semaphore).release(PERMIT_ID);
      assertThat(warnings()).hasSize(1);
      assertThat(warnings().get(0).getFormattedMessage())
          .isEqualTo("Permit [" + FULL_KEY + "] metrics recording failed: meter clash");
    }
  }

  @Nested
  @DisplayName("close on an unacquired handle")
  class Unacquired {

    @Test
    @DisplayName("reports acquired() false, permitId() null, and closes as a no-op")
    void noOp() {
      PermitHandle handle =
          new PermitHandle(null, null, FULL_KEY, Duration.ofSeconds(1), 0L, Duration.ZERO, metrics);

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
    @DisplayName("swallows IllegalArgumentException and logs the no-longer-held WARN, no trace")
    void swallowsIllegalArgument() {
      doThrow(
              new IllegalArgumentException(
                  "Permit with id permit-1 has already been released or doesn't exist"))
          .when(semaphore)
          .release(PERMIT_ID);
      PermitHandle handle = acquiredHandle();

      assertThatNoException().isThrownBy(handle::close);

      assertThat(warnings()).hasSize(1);
      ILoggingEvent warning = warnings().get(0);
      assertThat(warning.getFormattedMessage())
          .startsWith("Permit [" + FULL_KEY + "] was no longer held at release after ")
          .contains("(lease 1000ms); another instance may have run concurrently: ")
          .contains("has already been released or doesn't exist");
      assertThat(warning.getThrowableProxy()).isNull();
      verify(metrics, never()).recordHeld(any(), any());
    }

    @Test
    @DisplayName(
        "treats a RedisException caused by IllegalArgumentException, as Redisson 4.7 throws it,"
            + " as no longer held")
    void swallowsWrappedIllegalArgument() {
      doThrow(
              new RedisException(
                  "Unexpected exception while processing command",
                  new IllegalArgumentException(
                      "Permit with id permit-1 has already been released or doesn't exist")))
          .when(semaphore)
          .release(PERMIT_ID);
      PermitHandle handle = acquiredHandle();

      assertThatNoException().isThrownBy(handle::close);

      assertThat(warnings()).hasSize(1);
      ILoggingEvent warning = warnings().get(0);
      assertThat(warning.getFormattedMessage())
          .startsWith("Permit [" + FULL_KEY + "] was no longer held at release after ")
          .contains("(lease 1000ms); another instance may have run concurrently: ")
          .endsWith("has already been released or doesn't exist");
      assertThat(warning.getThrowableProxy()).isNull();
    }

    @Test
    @DisplayName("swallows any other RuntimeException and logs a WARN with the exception")
    void swallowsOtherRuntimeException() {
      RedisConnectionException failure = new RedisConnectionException("Redis down");
      doThrow(failure).when(semaphore).release(PERMIT_ID);
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

  @Nested
  @DisplayName("async release")
  class AsyncRelease {

    @Test
    @DisplayName("releases through releaseAsync, never the blocking release, and records held")
    void releasesAsync() {
      when(semaphore.releaseAsync(PERMIT_ID))
          .thenReturn(new CompletableFutureWrapper<>((Void) null));
      PermitHandle handle = acquiredHandle();

      CompletableFuture<Void> released = handle.releaseAsync().toCompletableFuture();
      handle.releaseAsync();

      assertThat(released).isCompletedWithValue(null);
      verify(semaphore).releaseAsync(PERMIT_ID);
      verify(semaphore, never()).release(any(String.class));
      verify(metrics).recordHeld(eq(Primitive.SEMAPHORE), any(Duration.class));
      assertThat(warnings()).isEmpty();
    }

    @Test
    @DisplayName(
        "an expired permit logs the same no-longer-held WARN; the stage completes normally")
    void expired() {
      when(semaphore.releaseAsync(PERMIT_ID))
          .thenReturn(
              new CompletableFutureWrapper<>(
                  new IllegalArgumentException(
                      "Permit with id permit-1 has already been released")));

      CompletableFuture<Void> released = acquiredHandle().releaseAsync().toCompletableFuture();

      assertThat(released).isCompletedWithValue(null);
      assertThat(warnings()).hasSize(1);
      assertThat(warnings().get(0).getFormattedMessage())
          .startsWith("Permit [" + FULL_KEY + "] was no longer held at release after ")
          .contains("(lease 1000ms); another instance may have run concurrently")
          .contains("has already been released");
      verify(metrics, never()).recordHeld(any(), any());
    }

    @Test
    @DisplayName("any other failure logs the release-failed WARN with the exception")
    void otherFailure() {
      when(semaphore.releaseAsync(PERMIT_ID))
          .thenReturn(new CompletableFutureWrapper<>(new RedisConnectionException("Redis down")));

      CompletableFuture<Void> released = acquiredHandle().releaseAsync().toCompletableFuture();

      assertThat(released).isCompletedWithValue(null);
      assertThat(warnings().get(0).getFormattedMessage())
          .startsWith("Permit [" + FULL_KEY + "] release failed after ")
          .contains("Redis down");
    }
  }
}
