package in.riido.locksmith.template;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import in.riido.locksmith.LockType;
import in.riido.locksmith.autoconfigure.LocksmithProperties;
import in.riido.locksmith.template.handle.LockHandle;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RReadWriteLock;
import org.redisson.api.RedissonClient;

@DisplayName("LocksmithLockTemplate Tests")
class LocksmithLockTemplateTest {

  private RedissonClient redissonClient;
  private LocksmithLockTemplate template;
  private RLock lock;

  @BeforeEach
  void setUp() {
    redissonClient = mock(RedissonClient.class);
    LocksmithProperties properties =
        new LocksmithProperties(
            new LocksmithProperties.LockProperties(
                true, Duration.ofMinutes(10), Duration.ofSeconds(60), "lock:", false, false),
            null,
            null);
    template = new LocksmithLockTemplate(redissonClient, properties);
    lock = mock(RLock.class);
  }

  @Nested
  @DisplayName("Builder tryLock Tests")
  class BuilderTryLockTests {

    @Test
    @DisplayName("Should acquire lock immediately with default parameters")
    void shouldAcquireLockImmediately() throws InterruptedException {
      when(redissonClient.getLock("lock:my-key")).thenReturn(lock);
      when(lock.tryLock(0, 600000, TimeUnit.MILLISECONDS)).thenReturn(true);

      try (LockHandle handle = template.withKey("my-key").tryLock()) {
        assertTrue(handle.isAcquired());
      }

      verify(redissonClient).getLock("lock:my-key");
      verify(lock).tryLock(0, 600000, TimeUnit.MILLISECONDS);
    }

    @Test
    @DisplayName("Should return not-acquired handle when lock not acquired")
    void shouldReturnNotAcquiredHandle() throws InterruptedException {
      when(redissonClient.getLock("lock:my-key")).thenReturn(lock);
      when(lock.tryLock(0, 600000, TimeUnit.MILLISECONDS)).thenReturn(false);

      try (LockHandle handle = template.withKey("my-key").tryLock()) {
        assertFalse(handle.isAcquired());
      }

      verify(lock, never()).unlock();
    }

    @Test
    @DisplayName("Should use custom wait time via builder")
    void shouldUseCustomWaitTime() throws InterruptedException {
      when(redissonClient.getLock("lock:my-key")).thenReturn(lock);
      when(lock.tryLock(5000, 600000, TimeUnit.MILLISECONDS)).thenReturn(true);

      try (LockHandle handle =
          template.withKey("my-key").waitTime(Duration.ofSeconds(5)).tryLock()) {
        assertTrue(handle.isAcquired());
      }

      verify(lock).tryLock(5000, 600000, TimeUnit.MILLISECONDS);
    }

    @Test
    @DisplayName("Should use custom lease time via builder")
    void shouldUseCustomLeaseTime() throws InterruptedException {
      when(redissonClient.getLock("lock:my-key")).thenReturn(lock);
      when(lock.tryLock(5000, 30000, TimeUnit.MILLISECONDS)).thenReturn(true);

      try (LockHandle handle =
          template
              .withKey("my-key")
              .waitTime(Duration.ofSeconds(5))
              .leaseTime(Duration.ofSeconds(30))
              .tryLock()) {
        assertTrue(handle.isAcquired());
      }

      verify(lock).tryLock(5000, 30000, TimeUnit.MILLISECONDS);
    }

    @Test
    @DisplayName("Should use autoRenew via builder")
    void shouldUseAutoRenew() throws InterruptedException {
      when(redissonClient.getLock("lock:my-key")).thenReturn(lock);
      when(lock.tryLock(0, -1, TimeUnit.MILLISECONDS)).thenReturn(true);

      try (LockHandle handle = template.withKey("my-key").autoRenew().tryLock()) {
        assertTrue(handle.isAcquired());
      }

      verify(lock).tryLock(0, -1, TimeUnit.MILLISECONDS);
    }

    @Test
    @DisplayName("Should return not-acquired handle when interrupted")
    void shouldReturnNotAcquiredWhenInterrupted() throws InterruptedException {
      when(redissonClient.getLock("lock:my-key")).thenReturn(lock);
      when(lock.tryLock(anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS)))
          .thenThrow(new InterruptedException());

      try (LockHandle handle = template.withKey("my-key").tryLock()) {
        assertFalse(handle.isAcquired());
      }

      assertTrue(Thread.currentThread().isInterrupted());
      Thread.interrupted(); // Clear interrupt status
    }

    @Test
    @DisplayName("Should auto-release lock on handle close")
    void shouldAutoReleaseLockOnClose() throws InterruptedException {
      when(redissonClient.getLock("lock:my-key")).thenReturn(lock);
      when(lock.tryLock(0, 600000, TimeUnit.MILLISECONDS)).thenReturn(true);

      LockHandle handle = template.withKey("my-key").tryLock();
      assertTrue(handle.isAcquired());
      handle.close();

      verify(lock).unlock();
    }
  }

  @Nested
  @DisplayName("Lock Type Tests via Builder")
  class LockTypeTests {

    private RReadWriteLock readWriteLock;
    private RLock readLock;
    private RLock writeLock;

    @BeforeEach
    void setUpReadWriteLock() {
      readWriteLock = mock(RReadWriteLock.class);
      readLock = mock(RLock.class);
      writeLock = mock(RLock.class);
      when(redissonClient.getReadWriteLock("lock:my-key")).thenReturn(readWriteLock);
      when(readWriteLock.readLock()).thenReturn(readLock);
      when(readWriteLock.writeLock()).thenReturn(writeLock);
    }

    @Test
    @DisplayName("Should use read lock for READ type via builder")
    void shouldUseReadLockForReadType() throws InterruptedException {
      when(readLock.tryLock(0, 600000, TimeUnit.MILLISECONDS)).thenReturn(true);

      try (LockHandle handle = template.withKey("my-key").lockType(LockType.READ).tryLock()) {
        assertTrue(handle.isAcquired());
      }

      verify(readWriteLock).readLock();
      verify(readWriteLock, never()).writeLock();
    }

    @Test
    @DisplayName("Should use write lock for WRITE type via builder")
    void shouldUseWriteLockForWriteType() throws InterruptedException {
      when(writeLock.tryLock(0, 600000, TimeUnit.MILLISECONDS)).thenReturn(true);

      try (LockHandle handle = template.withKey("my-key").lockType(LockType.WRITE).tryLock()) {
        assertTrue(handle.isAcquired());
      }

      verify(readWriteLock).writeLock();
      verify(readWriteLock, never()).readLock();
    }

    @Test
    @DisplayName("Should use reentrant lock for REENTRANT type via builder")
    void shouldUseReentrantLockForReentrantType() throws InterruptedException {
      when(redissonClient.getLock("lock:my-key")).thenReturn(lock);
      when(lock.tryLock(0, 600000, TimeUnit.MILLISECONDS)).thenReturn(true);

      try (LockHandle handle = template.withKey("my-key").lockType(LockType.REENTRANT).tryLock()) {
        assertTrue(handle.isAcquired());
      }

      verify(redissonClient).getLock("lock:my-key");
      verify(redissonClient, never()).getReadWriteLock(anyString());
    }
  }

  @Nested
  @DisplayName("unlock Tests")
  class UnlockTests {

    @Test
    @DisplayName("Should unlock reentrant lock via standalone method")
    void shouldUnlockReentrantLock() {
      when(redissonClient.getLock("lock:my-key")).thenReturn(lock);

      template.unlock("my-key");

      verify(lock).unlock();
    }

    @Test
    @DisplayName("Should unlock read lock via standalone method with type")
    void shouldUnlockReadLock() {
      RReadWriteLock readWriteLock = mock(RReadWriteLock.class);
      RLock readLock = mock(RLock.class);
      when(redissonClient.getReadWriteLock("lock:my-key")).thenReturn(readWriteLock);
      when(readWriteLock.readLock()).thenReturn(readLock);

      template.unlock("my-key", LockType.READ);

      verify(readLock).unlock();
    }

    @Test
    @DisplayName("Should unlock write lock via standalone method with type")
    void shouldUnlockWriteLock() {
      RReadWriteLock readWriteLock = mock(RReadWriteLock.class);
      RLock writeLock = mock(RLock.class);
      when(redissonClient.getReadWriteLock("lock:my-key")).thenReturn(readWriteLock);
      when(readWriteLock.writeLock()).thenReturn(writeLock);

      template.unlock("my-key", LockType.WRITE);

      verify(writeLock).unlock();
    }

    @Test
    @DisplayName("Should handle IllegalMonitorStateException gracefully")
    void shouldHandleIllegalMonitorStateException() {
      when(redissonClient.getLock("lock:my-key")).thenReturn(lock);
      doThrow(new IllegalMonitorStateException("Not held")).when(lock).unlock();

      assertDoesNotThrow(() -> template.unlock("my-key"));
    }
  }

  @Nested
  @DisplayName("isLocked Tests")
  class IsLockedTests {

    @Test
    @DisplayName("Should return true when lock is held")
    void shouldReturnTrueWhenLockIsHeld() {
      when(redissonClient.getLock("lock:my-key")).thenReturn(lock);
      when(lock.isLocked()).thenReturn(true);

      assertTrue(template.isLocked("my-key"));
    }

    @Test
    @DisplayName("Should return false when lock is not held")
    void shouldReturnFalseWhenLockIsNotHeld() {
      when(redissonClient.getLock("lock:my-key")).thenReturn(lock);
      when(lock.isLocked()).thenReturn(false);

      assertFalse(template.isLocked("my-key"));
    }

    @Test
    @DisplayName("Should check correct lock type via standalone method")
    void shouldCheckCorrectLockType() {
      RReadWriteLock readWriteLock = mock(RReadWriteLock.class);
      RLock writeLock = mock(RLock.class);
      when(redissonClient.getReadWriteLock("lock:my-key")).thenReturn(readWriteLock);
      when(readWriteLock.writeLock()).thenReturn(writeLock);
      when(writeLock.isLocked()).thenReturn(true);

      assertTrue(template.isLocked("my-key", LockType.WRITE));
      verify(readWriteLock).writeLock();
    }
  }

  @Nested
  @DisplayName("Builder execute Tests")
  class BuilderExecuteTests {

    @Test
    @DisplayName("Should execute callback when lock acquired")
    void shouldExecuteCallbackWhenLockAcquired() throws Exception {
      when(redissonClient.getLock("lock:my-key")).thenReturn(lock);
      when(lock.tryLock(0, 600000, TimeUnit.MILLISECONDS)).thenReturn(true);

      String result = template.withKey("my-key").execute(() -> "result");

      assertEquals("result", result);
      verify(lock).unlock();
    }

    @Test
    @DisplayName("Should return null when lock not acquired")
    void shouldReturnNullWhenLockNotAcquired() throws Exception {
      when(redissonClient.getLock("lock:my-key")).thenReturn(lock);
      when(lock.tryLock(0, 600000, TimeUnit.MILLISECONDS)).thenReturn(false);

      String result = template.withKey("my-key").execute(() -> "result");

      assertNull(result);
      verify(lock, never()).unlock();
    }

    @Test
    @DisplayName("Should release lock even when callback throws exception")
    void shouldReleaseLockWhenCallbackThrows() throws InterruptedException {
      when(redissonClient.getLock("lock:my-key")).thenReturn(lock);
      when(lock.tryLock(0, 600000, TimeUnit.MILLISECONDS)).thenReturn(true);

      assertThrows(
          RuntimeException.class,
          () ->
              template
                  .withKey("my-key")
                  .execute(
                      () -> {
                        throw new RuntimeException("Test error");
                      }));

      verify(lock).unlock();
    }

    @Test
    @DisplayName("Should return null when interrupted")
    void shouldReturnNullWhenInterrupted() throws Exception {
      when(redissonClient.getLock("lock:my-key")).thenReturn(lock);
      when(lock.tryLock(anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS)))
          .thenThrow(new InterruptedException());

      String result = template.withKey("my-key").execute(() -> "result");

      assertNull(result);
      assertTrue(Thread.currentThread().isInterrupted());
      Thread.interrupted(); // Clear interrupt status
    }

    @Test
    @DisplayName("Should use custom wait time in execute via builder")
    void shouldUseCustomWaitTimeInExecute() throws Exception {
      when(redissonClient.getLock("lock:my-key")).thenReturn(lock);
      when(lock.tryLock(5000, 600000, TimeUnit.MILLISECONDS)).thenReturn(true);

      template.withKey("my-key").waitTime(Duration.ofSeconds(5)).execute(() -> "result");

      verify(lock).tryLock(5000, 600000, TimeUnit.MILLISECONDS);
    }

    @Test
    @DisplayName("Should use custom lease time in execute via builder")
    void shouldUseCustomLeaseTimeInExecute() throws Exception {
      when(redissonClient.getLock("lock:my-key")).thenReturn(lock);
      when(lock.tryLock(0, 30000, TimeUnit.MILLISECONDS)).thenReturn(true);

      template.withKey("my-key").leaseTime(Duration.ofSeconds(30)).execute(() -> "result");

      verify(lock).tryLock(0, 30000, TimeUnit.MILLISECONDS);
    }

    @Test
    @DisplayName("Should use lock type in execute via builder")
    void shouldUseLockTypeInExecute() throws Exception {
      RReadWriteLock readWriteLock = mock(RReadWriteLock.class);
      RLock writeLock = mock(RLock.class);
      when(redissonClient.getReadWriteLock("lock:my-key")).thenReturn(readWriteLock);
      when(readWriteLock.writeLock()).thenReturn(writeLock);
      when(writeLock.tryLock(0, 600000, TimeUnit.MILLISECONDS)).thenReturn(true);

      template.withKey("my-key").lockType(LockType.WRITE).execute(() -> "result");

      verify(readWriteLock).writeLock();
      verify(writeLock).unlock();
    }

    @Test
    @DisplayName("Should use autoRenew in execute via builder")
    void shouldUseAutoRenewInExecute() throws Exception {
      when(redissonClient.getLock("lock:my-key")).thenReturn(lock);
      when(lock.tryLock(0, -1, TimeUnit.MILLISECONDS)).thenReturn(true);

      template.withKey("my-key").autoRenew().execute(() -> "result");

      verify(lock).tryLock(0, -1, TimeUnit.MILLISECONDS);
    }

    @Test
    @DisplayName("Should handle IllegalMonitorStateException during unlock")
    void shouldHandleIllegalMonitorStateExceptionInExecute() throws Exception {
      when(redissonClient.getLock("lock:my-key")).thenReturn(lock);
      when(lock.tryLock(0, 600000, TimeUnit.MILLISECONDS)).thenReturn(true);
      doThrow(new IllegalMonitorStateException("Expired")).when(lock).unlock();

      String result = template.withKey("my-key").execute(() -> "result");

      assertEquals("result", result);
    }
  }

  @Nested
  @DisplayName("Key Prefix Tests")
  class KeyPrefixTests {

    @Test
    @DisplayName("Should apply key prefix")
    void shouldApplyKeyPrefix() throws InterruptedException {
      when(redissonClient.getLock("lock:custom-key")).thenReturn(lock);
      when(lock.tryLock(anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(true);

      try (LockHandle handle = template.withKey("custom-key").tryLock()) {
        assertTrue(handle.isAcquired());
      }

      verify(redissonClient).getLock("lock:custom-key");
    }

    @Test
    @DisplayName("Should apply custom key prefix")
    void shouldApplyCustomKeyPrefix() throws InterruptedException {
      LocksmithProperties customProperties =
          new LocksmithProperties(
              new LocksmithProperties.LockProperties(
                  true, Duration.ofMinutes(10), Duration.ofSeconds(60), "myapp:", false, false),
              null,
              null);
      LocksmithLockTemplate customTemplate =
          new LocksmithLockTemplate(redissonClient, customProperties);

      when(redissonClient.getLock("myapp:custom-key")).thenReturn(lock);
      when(lock.tryLock(anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(true);

      try (LockHandle handle = customTemplate.withKey("custom-key").tryLock()) {
        assertTrue(handle.isAcquired());
      }

      verify(redissonClient).getLock("myapp:custom-key");
    }
  }
}
