package in.riido.locksmith.template;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import in.riido.locksmith.autoconfigure.LocksmithProperties;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RPermitExpirableSemaphore;
import org.redisson.api.RedissonClient;

@DisplayName("LocksmithSemaphoreTemplate Tests")
class LocksmithSemaphoreTemplateTest {

  private RedissonClient redissonClient;
  private LocksmithSemaphoreTemplate template;
  private RPermitExpirableSemaphore semaphore;
  private RBucket<Integer> metaBucket;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    redissonClient = mock(RedissonClient.class);
    LocksmithProperties properties =
        new LocksmithProperties(
            null,
            new LocksmithProperties.SemaphoreProperties(
                Duration.ofMinutes(5), Duration.ofSeconds(60), "semaphore:", false));
    template = new LocksmithSemaphoreTemplate(redissonClient, properties);
    semaphore = mock(RPermitExpirableSemaphore.class);
    metaBucket = mock(RBucket.class);
    final var keys = redissonClient.getKeys();
    if (keys != null && keys.count() > 0) {
      keys.flushall();
    }
  }

  private void setupSemaphore(String key) {
    when(redissonClient.getPermitExpirableSemaphore("semaphore:" + key)).thenReturn(semaphore);
    when(redissonClient.<Integer>getBucket("semaphore:" + key + ":meta")).thenReturn(metaBucket);
    when(metaBucket.get()).thenReturn(null);
    when(semaphore.trySetPermits(anyInt())).thenReturn(true);
  }

  @Nested
  @DisplayName("Simple tryAcquirePermit Tests")
  class SimpleTryAcquirePermitTests {

    @Test
    @DisplayName("Should acquire permit immediately with default parameters")
    void shouldAcquirePermitImmediately() throws InterruptedException {
      setupSemaphore("my-key");
      when(semaphore.tryAcquire(0, 300000, TimeUnit.MILLISECONDS)).thenReturn("permit-123");

      String permitId = template.tryAcquirePermit("my-key", 5);

      assertEquals("permit-123", permitId);
      verify(semaphore).trySetPermits(5);
      verify(metaBucket).set(5);
    }

    @Test
    @DisplayName("Should return null when permit not acquired")
    void shouldReturnNullWhenPermitNotAcquired() throws InterruptedException {
      setupSemaphore("my-key");
      when(semaphore.tryAcquire(0, 300000, TimeUnit.MILLISECONDS)).thenReturn(null);

      String permitId = template.tryAcquirePermit("my-key", 5);

      assertNull(permitId);
    }

    @Test
    @DisplayName("Should throw exception for non-positive permits")
    void shouldThrowExceptionForNonPositivePermits() {
      assertThrows(IllegalArgumentException.class, () -> template.tryAcquirePermit("my-key", 0));

      assertThrows(IllegalArgumentException.class, () -> template.tryAcquirePermit("my-key", -1));
    }

    @Test
    @DisplayName("Should return null when interrupted")
    void shouldReturnNullWhenInterrupted() throws InterruptedException {
      setupSemaphore("my-key");
      when(semaphore.tryAcquire(anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS)))
          .thenThrow(new InterruptedException());

      String permitId = template.tryAcquirePermit("my-key", 5);

      assertNull(permitId);
      assertTrue(Thread.currentThread().isInterrupted());
      Thread.interrupted(); // Clear interrupt status
    }
  }

  @Nested
  @DisplayName("Builder tryAcquire Tests")
  class BuilderTryAcquireTests {

    @Test
    @DisplayName("Should use custom wait time via builder")
    void shouldUseCustomWaitTime() throws InterruptedException {
      setupSemaphore("my-key");
      when(semaphore.tryAcquire(5000, 300000, TimeUnit.MILLISECONDS)).thenReturn("permit-123");

      String permitId = template.forKey("my-key", 5).waitTime(Duration.ofSeconds(5)).tryAcquire();

      assertEquals("permit-123", permitId);
      verify(semaphore).tryAcquire(5000, 300000, TimeUnit.MILLISECONDS);
    }

    @Test
    @DisplayName("Should use custom lease time via builder")
    void shouldUseCustomLeaseTime() throws InterruptedException {
      setupSemaphore("my-key");
      when(semaphore.tryAcquire(0, 60000, TimeUnit.MILLISECONDS)).thenReturn("permit-123");

      String permitId = template.forKey("my-key", 5).leaseTime(Duration.ofMinutes(1)).tryAcquire();

      assertEquals("permit-123", permitId);
      verify(semaphore).tryAcquire(0, 60000, TimeUnit.MILLISECONDS);
    }

    @Test
    @DisplayName("Should use custom wait and lease time via builder")
    void shouldUseCustomWaitAndLeaseTime() throws InterruptedException {
      setupSemaphore("my-key");
      when(semaphore.tryAcquire(10000, 120000, TimeUnit.MILLISECONDS)).thenReturn("permit-123");

      String permitId =
          template
              .forKey("my-key", 5)
              .waitTime(Duration.ofSeconds(10))
              .leaseTime(Duration.ofMinutes(2))
              .tryAcquire();

      assertEquals("permit-123", permitId);
      verify(semaphore).tryAcquire(10000, 120000, TimeUnit.MILLISECONDS);
    }
  }

  @Nested
  @DisplayName("releasePermit Tests")
  class ReleasePermitTests {

    @Test
    @DisplayName("Should release permit")
    void shouldReleasePermit() {
      when(redissonClient.getPermitExpirableSemaphore("semaphore:my-key")).thenReturn(semaphore);

      template.releasePermit("my-key", "permit-123");

      verify(semaphore).release("permit-123");
    }

    @Test
    @DisplayName("Should handle IllegalArgumentException gracefully")
    void shouldHandleIllegalArgumentException() {
      when(redissonClient.getPermitExpirableSemaphore("semaphore:my-key")).thenReturn(semaphore);
      doThrow(new IllegalArgumentException("Permit expired")).when(semaphore).release("permit-123");

      // Should not throw
      assertDoesNotThrow(() -> template.releasePermit("my-key", "permit-123"));
    }

    @Test
    @DisplayName("Should handle other exceptions gracefully")
    void shouldHandleOtherExceptionsGracefully() {
      when(redissonClient.getPermitExpirableSemaphore("semaphore:my-key")).thenReturn(semaphore);
      doThrow(new RuntimeException("Redis error")).when(semaphore).release("permit-123");

      // Should not throw
      assertDoesNotThrow(() -> template.releasePermit("my-key", "permit-123"));
    }
  }

  @Nested
  @DisplayName("Simple executeWithPermit Tests")
  class SimpleExecuteWithPermitTests {

    @Test
    @DisplayName("Should execute callback when permit acquired")
    void shouldExecuteCallbackWhenPermitAcquired() throws Exception {
      setupSemaphore("my-key");
      when(semaphore.tryAcquire(0, 300000, TimeUnit.MILLISECONDS)).thenReturn("permit-123");

      String result = template.executeWithPermit("my-key", 5, () -> "result");

      assertEquals("result", result);
      verify(semaphore).release("permit-123");
    }

    @Test
    @DisplayName("Should return null when permit not acquired")
    void shouldReturnNullWhenPermitNotAcquired() throws Exception {
      setupSemaphore("my-key");
      when(semaphore.tryAcquire(0, 300000, TimeUnit.MILLISECONDS)).thenReturn(null);

      String result = template.executeWithPermit("my-key", 5, () -> "result");

      assertNull(result);
      verify(semaphore, never()).release(anyString());
    }

    @Test
    @DisplayName("Should release permit even when callback throws exception")
    void shouldReleasePermitWhenCallbackThrows() throws InterruptedException {
      setupSemaphore("my-key");
      when(semaphore.tryAcquire(0, 300000, TimeUnit.MILLISECONDS)).thenReturn("permit-123");

      assertThrows(
          RuntimeException.class,
          () ->
              template.executeWithPermit(
                  "my-key",
                  5,
                  () -> {
                    throw new RuntimeException("Test error");
                  }));

      verify(semaphore).release("permit-123");
    }

    @Test
    @DisplayName("Should throw exception for non-positive permits in executeWithPermit")
    void shouldThrowExceptionForNonPositivePermitsInExecuteWithPermit() {
      assertThrows(
          IllegalArgumentException.class,
          () -> template.executeWithPermit("my-key", 0, () -> "result"));

      assertThrows(
          IllegalArgumentException.class,
          () -> template.executeWithPermit("my-key", -1, () -> "result"));
    }

    @Test
    @DisplayName("Should return null when interrupted")
    void shouldReturnNullWhenInterrupted() throws Exception {
      setupSemaphore("my-key");
      when(semaphore.tryAcquire(anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS)))
          .thenThrow(new InterruptedException());

      String result = template.executeWithPermit("my-key", 5, () -> "result");

      assertNull(result);
      assertTrue(Thread.currentThread().isInterrupted());
      Thread.interrupted(); // Clear interrupt status
    }

    @Test
    @DisplayName("Should handle IllegalArgumentException during release in executeWithPermit")
    void shouldHandleIllegalArgumentExceptionInExecuteWithPermit() throws Exception {
      setupSemaphore("my-key");
      when(semaphore.tryAcquire(0, 300000, TimeUnit.MILLISECONDS)).thenReturn("permit-123");
      doThrow(new IllegalArgumentException("Expired")).when(semaphore).release("permit-123");

      String result = template.executeWithPermit("my-key", 5, () -> "result");

      assertEquals("result", result);
    }
  }

  @Nested
  @DisplayName("Builder execute Tests")
  class BuilderExecuteTests {

    @Test
    @DisplayName("Should use custom wait time in execute via builder")
    void shouldUseCustomWaitTimeInExecute() throws Exception {
      setupSemaphore("my-key");
      when(semaphore.tryAcquire(5000, 300000, TimeUnit.MILLISECONDS)).thenReturn("permit-123");

      template.forKey("my-key", 5).waitTime(Duration.ofSeconds(5)).execute(() -> "result");

      verify(semaphore).tryAcquire(5000, 300000, TimeUnit.MILLISECONDS);
    }

    @Test
    @DisplayName("Should use custom lease time in execute via builder")
    void shouldUseCustomLeaseTimeInExecute() throws Exception {
      setupSemaphore("my-key");
      when(semaphore.tryAcquire(0, 60000, TimeUnit.MILLISECONDS)).thenReturn("permit-123");

      template.forKey("my-key", 5).leaseTime(Duration.ofMinutes(1)).execute(() -> "result");

      verify(semaphore).tryAcquire(0, 60000, TimeUnit.MILLISECONDS);
    }
  }

  @Nested
  @DisplayName("Semaphore Initialization Tests")
  class SemaphoreInitializationTests {

    @Test
    @DisplayName("Should initialize semaphore with permits")
    void shouldInitializeSemaphoreWithPermits() throws InterruptedException {
      setupSemaphore("my-key");
      when(semaphore.tryAcquire(0, 300000, TimeUnit.MILLISECONDS)).thenReturn("permit-123");

      template.tryAcquirePermit("my-key", 5);

      verify(semaphore).trySetPermits(5);
      verify(metaBucket).set(5);
    }

    @Test
    @DisplayName("Should not reinitialize semaphore on second call")
    void shouldNotReinitializeSemaphoreOnSecondCall() throws InterruptedException {
      setupSemaphore("my-key");
      when(semaphore.tryAcquire(anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS)))
          .thenReturn("permit-123");

      template.tryAcquirePermit("my-key", 5);
      template.tryAcquirePermit("my-key", 5);

      verify(semaphore, times(1)).trySetPermits(5);
    }

    @Test
    @DisplayName("Should use existing semaphore permits")
    void shouldUseExistingSemaphorePermits() throws InterruptedException {
      when(redissonClient.getPermitExpirableSemaphore("semaphore:my-key")).thenReturn(semaphore);
      when(redissonClient.<Integer>getBucket("semaphore:my-key:meta")).thenReturn(metaBucket);
      when(metaBucket.get()).thenReturn(10); // Existing value
      when(semaphore.tryAcquire(0, 300000, TimeUnit.MILLISECONDS)).thenReturn("permit-123");

      template.tryAcquirePermit("my-key", 5);

      verify(semaphore, never()).trySetPermits(anyInt());
    }

    @Test
    @DisplayName("Should warn when permit count mismatch with existing semaphore")
    void shouldWarnWhenPermitCountMismatch() throws InterruptedException {
      when(redissonClient.getPermitExpirableSemaphore("semaphore:my-key")).thenReturn(semaphore);
      when(redissonClient.<Integer>getBucket("semaphore:my-key:meta")).thenReturn(metaBucket);
      when(metaBucket.get()).thenReturn(10); // Different from requested 5
      when(semaphore.tryAcquire(0, 300000, TimeUnit.MILLISECONDS)).thenReturn("permit-123");

      String permitId = template.tryAcquirePermit("my-key", 5);

      assertNotNull(permitId);
      verify(semaphore, never()).trySetPermits(anyInt());
    }
  }

  @Nested
  @DisplayName("Key Prefix Tests")
  class KeyPrefixTests {

    @Test
    @DisplayName("Should apply key prefix")
    void shouldApplyKeyPrefix() throws InterruptedException {
      setupSemaphore("custom-key");
      when(semaphore.tryAcquire(anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS)))
          .thenReturn("permit-123");

      template.tryAcquirePermit("custom-key", 5);

      verify(redissonClient, atLeastOnce()).getPermitExpirableSemaphore("semaphore:custom-key");
    }

    @Test
    @DisplayName("Should apply custom key prefix")
    void shouldApplyCustomKeyPrefix() throws InterruptedException {
      LocksmithProperties customProperties =
          new LocksmithProperties(
              null,
              new LocksmithProperties.SemaphoreProperties(
                  Duration.ofMinutes(5), Duration.ofSeconds(60), "myapp:", false));
      LocksmithSemaphoreTemplate customTemplate =
          new LocksmithSemaphoreTemplate(redissonClient, customProperties);

      when(redissonClient.getPermitExpirableSemaphore("myapp:custom-key")).thenReturn(semaphore);
      when(redissonClient.<Integer>getBucket("myapp:custom-key:meta")).thenReturn(metaBucket);
      when(metaBucket.get()).thenReturn(null);
      when(semaphore.trySetPermits(5)).thenReturn(true);
      when(semaphore.tryAcquire(anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS)))
          .thenReturn("permit-123");

      customTemplate.tryAcquirePermit("custom-key", 5);

      verify(redissonClient, atLeastOnce()).getPermitExpirableSemaphore("myapp:custom-key");
    }
  }
}
