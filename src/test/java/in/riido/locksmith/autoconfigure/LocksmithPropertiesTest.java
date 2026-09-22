package in.riido.locksmith.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.riido.locksmith.LocksmithConfigurationException;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("LocksmithProperties")
class LocksmithPropertiesTest {

  @Nested
  @DisplayName("defaults")
  class Defaults {

    @Test
    @DisplayName("all nulls give enabled=true, prefix locksmith: and lease 5m")
    void allNullsGiveDefaults() {
      LocksmithProperties properties = new LocksmithProperties(null, null, null);

      assertThat(properties.enabled()).isTrue();
      assertThat(properties.keyPrefix()).isEqualTo("locksmith:");
      assertThat(properties.semaphore().leaseTime()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("blank key prefix falls back to locksmith:")
    void blankKeyPrefixFallsBack() {
      assertThat(new LocksmithProperties(null, "  ", null).keyPrefix()).isEqualTo("locksmith:");
    }

    @Test
    @DisplayName("null semaphore lease time falls back to 5m")
    void nullLeaseTimeFallsBack() {
      assertThat(new LocksmithProperties.Semaphore(null).leaseTime())
          .isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("explicit values are kept")
    void explicitValuesAreKept() {
      LocksmithProperties properties =
          new LocksmithProperties(
              false, "app:", new LocksmithProperties.Semaphore(Duration.ofSeconds(30)));

      assertThat(properties.enabled()).isFalse();
      assertThat(properties.keyPrefix()).isEqualTo("app:");
      assertThat(properties.semaphore().leaseTime()).isEqualTo(Duration.ofSeconds(30));
    }
  }

  @Nested
  @DisplayName("semaphore lease time")
  class LeaseTime {

    @Test
    @DisplayName("zero is rejected with a message containing the value")
    void zeroIsRejected() {
      assertThatThrownBy(() -> new LocksmithProperties.Semaphore(Duration.ZERO))
          .isInstanceOf(LocksmithConfigurationException.class)
          .hasMessageContaining("lease-time")
          .hasMessageContaining("PT0S");
    }

    @Test
    @DisplayName("negative is rejected with a message containing the value")
    void negativeIsRejected() {
      assertThatThrownBy(() -> new LocksmithProperties.Semaphore(Duration.ofSeconds(-1)))
          .isInstanceOf(LocksmithConfigurationException.class)
          .hasMessageContaining("lease-time")
          .hasMessageContaining("PT-1S");
    }

    @Test
    @DisplayName("below one millisecond is rejected with a message containing the value")
    void subMillisecondIsRejected() {
      assertThatThrownBy(() -> new LocksmithProperties.Semaphore(Duration.ofNanos(500)))
          .isInstanceOf(LocksmithConfigurationException.class)
          .hasMessageContaining("lease-time must be at least one millisecond")
          .hasMessageContaining("PT0.0000005S");
    }
  }
}
