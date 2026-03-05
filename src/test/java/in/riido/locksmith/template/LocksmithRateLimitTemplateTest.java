package in.riido.locksmith.template;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import in.riido.locksmith.autoconfigure.LocksmithProperties;
import in.riido.locksmith.exception.RateLimitConfigurationException;
import in.riido.locksmith.metrics.RateLimitMetrics;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RedissonClient;

@ExtendWith(MockitoExtension.class)
@DisplayName("LocksmithRateLimitTemplate Tests")
class LocksmithRateLimitTemplateTest {

  @Mock private RedissonClient redissonClient;
  @Mock private RRateLimiter rateLimiter;
  @Mock private RateLimitMetrics metrics;

  private LocksmithRateLimitTemplate template;
  private LocksmithProperties properties;

  @BeforeEach
  void setUp() {
    properties =
        new LocksmithProperties(
            null,
            null,
            new LocksmithProperties.RateLimitProperties(
                true, Duration.ofMinutes(1), "ratelimit:", false, false));
    template = new LocksmithRateLimitTemplate(redissonClient, properties);
  }

  @Nested
  @DisplayName("Builder TryAcquire Tests")
  class BuilderTryAcquireTests {

    @Test
    @DisplayName("Should acquire rate limit successfully")
    void shouldAcquireRateLimitSuccessfully() {
      when(redissonClient.getRateLimiter("ratelimit:test-key")).thenReturn(rateLimiter);
      when(rateLimiter.trySetRate(any(), anyLong(), any())).thenReturn(true);
      when(rateLimiter.tryAcquire()).thenReturn(true);

      boolean acquired = template.withKey("test-key").tryAcquire();

      assertTrue(acquired);
      verify(rateLimiter).trySetRate(any(), eq(10L), eq(Duration.ofSeconds(1)));
      verify(rateLimiter).tryAcquire();
    }

    @Test
    @DisplayName("Should return false when rate limit exceeded")
    void shouldReturnFalseWhenRateLimitExceeded() {
      when(redissonClient.getRateLimiter("ratelimit:test-key")).thenReturn(rateLimiter);
      when(rateLimiter.trySetRate(any(), anyLong(), any())).thenReturn(true);
      when(rateLimiter.tryAcquire()).thenReturn(false);

      boolean acquired = template.withKey("test-key").tryAcquire();

      assertFalse(acquired);
    }

    @Test
    @DisplayName("Should use key prefix from properties")
    void shouldUseKeyPrefixFromProperties() {
      when(redissonClient.getRateLimiter("ratelimit:my-key")).thenReturn(rateLimiter);
      when(rateLimiter.trySetRate(any(), anyLong(), any())).thenReturn(true);
      when(rateLimiter.tryAcquire()).thenReturn(true);

      template.withKey("my-key").tryAcquire();

      verify(redissonClient).getRateLimiter("ratelimit:my-key");
    }

    @Test
    @DisplayName("Should configure permits via builder")
    void shouldConfigurePermitsViaBuilder() {
      when(redissonClient.getRateLimiter("ratelimit:test-key")).thenReturn(rateLimiter);
      when(rateLimiter.trySetRate(any(), anyLong(), any())).thenReturn(true);
      when(rateLimiter.tryAcquire()).thenReturn(true);

      template.withKey("test-key").permits(20).tryAcquire();

      verify(rateLimiter).trySetRate(any(), eq(20L), any());
    }

    @Test
    @DisplayName("Should configure interval via builder")
    void shouldConfigureIntervalViaBuilder() {
      when(redissonClient.getRateLimiter("ratelimit:test-key")).thenReturn(rateLimiter);
      when(rateLimiter.trySetRate(any(), anyLong(), any())).thenReturn(true);
      when(rateLimiter.tryAcquire()).thenReturn(true);

      template.withKey("test-key").interval(Duration.ofMinutes(1)).tryAcquire();

      verify(rateLimiter).trySetRate(any(), anyLong(), eq(Duration.ofMinutes(1)));
    }

    @Test
    @DisplayName("Should configure wait time via builder")
    void shouldConfigureWaitTimeViaBuilder() {
      when(redissonClient.getRateLimiter("ratelimit:test-key")).thenReturn(rateLimiter);
      when(rateLimiter.trySetRate(any(), anyLong(), any())).thenReturn(true);
      when(rateLimiter.tryAcquire(eq(1L), eq(Duration.ofSeconds(5)))).thenReturn(true);

      template.withKey("test-key").waitTime(Duration.ofSeconds(5)).tryAcquire();

      verify(rateLimiter).tryAcquire(eq(1L), eq(Duration.ofSeconds(5)));
    }

    @Test
    @DisplayName("Should chain multiple configurations")
    void shouldChainMultipleConfigurations() {
      when(redissonClient.getRateLimiter("ratelimit:chained-key")).thenReturn(rateLimiter);
      when(rateLimiter.trySetRate(any(), anyLong(), any())).thenReturn(true);
      when(rateLimiter.tryAcquire(eq(1L), eq(Duration.ofSeconds(10)))).thenReturn(true);

      boolean acquired =
          template
              .withKey("chained-key")
              .permits(50)
              .interval(Duration.ofMinutes(1))
              .waitTime(Duration.ofSeconds(10))
              .tryAcquire();

      assertTrue(acquired);
      verify(rateLimiter).trySetRate(any(), eq(50L), eq(Duration.ofMinutes(1)));
      verify(rateLimiter).tryAcquire(eq(1L), eq(Duration.ofSeconds(10)));
    }
  }

  @Nested
  @DisplayName("Builder Execute Tests")
  class BuilderExecuteTests {

    @Test
    @DisplayName("Should execute callback when rate limit acquired")
    void shouldExecuteCallbackWhenRateLimitAcquired() throws Exception {
      when(redissonClient.getRateLimiter("ratelimit:test-key")).thenReturn(rateLimiter);
      when(rateLimiter.trySetRate(any(), anyLong(), any())).thenReturn(true);
      when(rateLimiter.tryAcquire()).thenReturn(true);

      String result = template.withKey("test-key").execute(() -> "success");

      assertEquals("success", result);
    }

    @Test
    @DisplayName("Should return null when rate limit not acquired")
    void shouldReturnNullWhenRateLimitNotAcquired() throws Exception {
      when(redissonClient.getRateLimiter("ratelimit:test-key")).thenReturn(rateLimiter);
      when(rateLimiter.trySetRate(any(), anyLong(), any())).thenReturn(true);
      when(rateLimiter.tryAcquire()).thenReturn(false);

      String result = template.withKey("test-key").execute(() -> "should-not-execute");

      assertNull(result);
    }

    @Test
    @DisplayName("Should propagate exception from callback")
    void shouldPropagateExceptionFromCallback() {
      when(redissonClient.getRateLimiter("ratelimit:test-key")).thenReturn(rateLimiter);
      when(rateLimiter.trySetRate(any(), anyLong(), any())).thenReturn(true);
      when(rateLimiter.tryAcquire()).thenReturn(true);

      assertThrows(
          RuntimeException.class,
          () ->
              template
                  .withKey("test-key")
                  .execute(
                      () -> {
                        throw new RuntimeException("Test error");
                      }));
    }

    @Test
    @DisplayName("Should execute callback via builder with custom config")
    void shouldExecuteCallbackViaBuilder() throws Exception {
      when(redissonClient.getRateLimiter("ratelimit:test-key")).thenReturn(rateLimiter);
      when(rateLimiter.trySetRate(any(), anyLong(), any())).thenReturn(true);
      when(rateLimiter.tryAcquire()).thenReturn(true);

      AtomicInteger counter = new AtomicInteger(0);
      String result =
          template
              .withKey("test-key")
              .permits(10)
              .interval(Duration.ofSeconds(1))
              .execute(
                  () -> {
                    counter.incrementAndGet();
                    return "executed";
                  });

      assertEquals("executed", result);
      assertEquals(1, counter.get());
    }
  }

  @Nested
  @DisplayName("Metrics Tests")
  class MetricsTests {

    @Test
    @DisplayName("Should record metrics when enabled")
    void shouldRecordMetricsWhenEnabled() throws Exception {
      properties =
          new LocksmithProperties(
              null,
              null,
              new LocksmithProperties.RateLimitProperties(
                  true, Duration.ofMinutes(1), "ratelimit:", false, true));
      template = new LocksmithRateLimitTemplate(redissonClient, properties, metrics);

      when(redissonClient.getRateLimiter("ratelimit:test-key")).thenReturn(rateLimiter);
      when(rateLimiter.trySetRate(any(), anyLong(), any())).thenReturn(true);
      when(rateLimiter.tryAcquire()).thenReturn(true);

      template.withKey("test-key").execute(() -> "result");

      verify(metrics).recordAcquired();
      verify(metrics).recordAcquisitionTime(anyLong());
      verify(metrics).recordExecutionTime(anyLong());
    }

    @Test
    @DisplayName("Should record exceeded metric when rate limit not acquired")
    void shouldRecordExceededMetricWhenNotAcquired() {
      properties =
          new LocksmithProperties(
              null,
              null,
              new LocksmithProperties.RateLimitProperties(
                  true, Duration.ofMinutes(1), "ratelimit:", false, true));
      template = new LocksmithRateLimitTemplate(redissonClient, properties, metrics);

      when(redissonClient.getRateLimiter("ratelimit:test-key")).thenReturn(rateLimiter);
      when(rateLimiter.trySetRate(any(), anyLong(), any())).thenReturn(true);
      when(rateLimiter.tryAcquire()).thenReturn(false);

      template.withKey("test-key").tryAcquire();

      verify(metrics).recordExceeded("immediate");
    }
  }

  @Nested
  @DisplayName("Builder Validation Tests")
  class BuilderValidationTests {

    @Test
    @DisplayName("Should throw exception for zero permits via builder")
    void shouldThrowExceptionForZeroPermitsViaBuilder() {
      assertThrows(
          RateLimitConfigurationException.class,
          () -> template.withKey("test-key").permits(0).tryAcquire());
    }

    @Test
    @DisplayName("Should throw exception for negative permits via builder")
    void shouldThrowExceptionForNegativePermitsViaBuilder() {
      assertThrows(
          RateLimitConfigurationException.class,
          () -> template.withKey("test-key").permits(-1).tryAcquire());
    }
  }
}
