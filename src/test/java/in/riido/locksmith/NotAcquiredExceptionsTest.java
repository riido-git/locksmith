package in.riido.locksmith;

import static org.assertj.core.api.Assertions.assertThat;

import in.riido.locksmith.lock.LockNotAcquiredException;
import in.riido.locksmith.semaphore.SemaphoreNotAcquiredException;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("not-acquired exceptions")
class NotAcquiredExceptionsTest {

  @Test
  @DisplayName("lock exception carries key and wait time and uses the fixed message")
  void lockExceptionMessageAndFields() {
    LockNotAcquiredException e =
        new LockNotAcquiredException("locksmith:lock:scheduler:cleanup", Duration.ZERO);

    assertThat(e).isInstanceOf(LocksmithException.class);
    assertThat(e.key()).isEqualTo("locksmith:lock:scheduler:cleanup");
    assertThat(e.waitTime()).isEqualTo(Duration.ZERO);
    assertThat(e).hasMessage("Lock [locksmith:lock:scheduler:cleanup] not acquired within PT0S");
  }

  @Test
  @DisplayName("semaphore exception carries key, permits and wait time and uses the fixed message")
  void semaphoreExceptionMessageAndFields() {
    SemaphoreNotAcquiredException e =
        new SemaphoreNotAcquiredException("locksmith:semaphore:reports", 5, Duration.ofSeconds(2));

    assertThat(e).isInstanceOf(LocksmithException.class);
    assertThat(e.key()).isEqualTo("locksmith:semaphore:reports");
    assertThat(e.permits()).isEqualTo(5);
    assertThat(e.waitTime()).isEqualTo(Duration.ofSeconds(2));
    assertThat(e)
        .hasMessage(
            "Semaphore [locksmith:semaphore:reports] permit not acquired within PT2S (permits 5)");
  }
}
